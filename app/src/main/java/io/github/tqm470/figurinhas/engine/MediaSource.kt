package io.github.tqm470.figurinhas.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class MediaKind { GIF, VIDEO, IMAGE }

/**
 * A decoded input (GIF, video or still image) that can produce frames sampled
 * at arbitrary times, scaled so the short side is at most [MAX_SHORT_SIDE].
 */
class MediaSource private constructor(
    private val file: File,
    val kind: MediaKind,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    /** Frame rate of the source, if known (GIFs / videos). */
    val nativeFps: Float,
) {

    /**
     * Returns one bitmap per sample time: start, start+step, ... (< end).
     * Consecutive samples may share the same Bitmap instance when the source
     * didn't change (e.g. a slow GIF), which saves memory and encoding work.
     */
    suspend fun frames(startMs: Long, endMs: Long, stepMs: Double, onProgress: (Float) -> Unit = {}): List<Bitmap> =
        withContext(Dispatchers.Default) {
            when (kind) {
                MediaKind.IMAGE -> listOf(decodeImage())
                MediaKind.GIF -> gifFrames(startMs, endMs, stepMs, onProgress)
                MediaKind.VIDEO -> videoFrames(startMs, endMs, stepMs, onProgress)
            }
        }

    private fun targetSize(w: Int, h: Int): Pair<Int, Int> {
        val scale = min(1f, MAX_SHORT_SIDE.toFloat() / min(w, h))
        return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
    }

    private fun scaled(src: Bitmap, config: Bitmap.Config): Bitmap {
        val (tw, th) = targetSize(src.width, src.height)
        val s = if (tw != src.width || th != src.height) Bitmap.createScaledBitmap(src, tw, th, true) else src
        return if (s.config != config) s.copy(config, false).also { if (s !== src) s.recycle() }
        else if (s === src) src.copy(config, false) else s
    }

    // ---------- Still images ----------

    private fun decodeImage(): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(file)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (tw, th) = targetSize(info.size.width, info.size.height)
                decoder.setTargetSize(tw, th)
            }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SHORT_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Can't decode image")
        return scaled(bmp, Bitmap.Config.ARGB_8888)
    }

    // ---------- GIF ----------

    private suspend fun gifFrames(startMs: Long, endMs: Long, stepMs: Double, onProgress: (Float) -> Unit): List<Bitmap> {
        val decoder = openGif(file)
        val out = ArrayList<Bitmap>()
        var t = 0L
        var sampleIndex = 0
        fun sampleTime(i: Int) = startMs + i * stepMs
        val total = max(1.0, (endMs - startMs) / stepMs)
        try {
            for (i in 0 until decoder.frameCount) {
                coroutineContext.ensureActive()
                decoder.advance()
                val delay = fixGifDelay(decoder.getDelay(decoder.currentFrameIndex))
                val frameEnd = t + delay
                if (frameEnd > sampleTime(sampleIndex)) {
                    val raw = decoder.nextFrame ?: break
                    var shared: Bitmap? = null
                    while (sampleTime(sampleIndex) < frameEnd && sampleTime(sampleIndex) < endMs) {
                        if (sampleTime(sampleIndex) >= t) {
                            val b = shared ?: scaled(raw, Bitmap.Config.ARGB_8888).also { shared = it }
                            out += b
                        }
                        sampleIndex++
                        onProgress((sampleIndex / total).toFloat().coerceAtMost(1f))
                    }
                }
                t = frameEnd
                if (sampleTime(sampleIndex) >= endMs) break
            }
        } finally {
            decoder.clear()
        }
        if (out.isEmpty()) {
            // Window started past the last frame (shouldn't happen), fall back to first frame.
            val d = openGif(file)
            d.advance()
            d.nextFrame?.let { out += scaled(it, Bitmap.Config.ARGB_8888) }
            d.clear()
        }
        return out
    }

    // ---------- Video ----------

    private suspend fun videoFrames(startMs: Long, endMs: Long, stepMs: Double, onProgress: (Float) -> Unit): List<Bitmap> {
        val times = generateSequence(0) { it + 1 }
            .map { startMs + it * stepMs }
            .takeWhile { it < endMs }
            .toList()
            .ifEmpty { listOf(startMs.toDouble()) }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            if (Build.VERSION.SDK_INT >= 28) {
                runCatching { return framesByIndex(r, times, onProgress) }
            }
            val (tw, th) = targetSize(width, height)
            return times.mapIndexed { i, ms ->
                coroutineContext.ensureActive()
                val us = (ms * 1000).toLong()
                val bmp = if (Build.VERSION.SDK_INT >= 27) {
                    r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, tw, th)
                } else {
                    r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
                } ?: r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: error("Can't read video frame")
                onProgress((i + 1f) / times.size)
                scaled(bmp, Bitmap.Config.RGB_565).also { if (it !== bmp) bmp.recycle() }
            }
        } finally {
            r.release()
        }
    }

    /** Decodes frames sequentially in small batches — much faster than seeking per frame. */
    @androidx.annotation.RequiresApi(28)
    private suspend fun framesByIndex(
        r: MediaMetadataRetriever, times: List<Double>, onProgress: (Float) -> Unit,
    ): List<Bitmap> {
        val count = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            ?: error("no frame count")
        if (count <= 0 || durationMs <= 0) error("no frame count")
        val fps = count * 1000.0 / durationMs
        val wanted = times.map { (it * fps / 1000.0).toInt().coerceIn(0, count - 1) }
        val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 }
        val result = arrayOfNulls<Bitmap>(wanted.size)
        var w = 0
        while (w < wanted.size) {
            coroutineContext.ensureActive()
            val first = wanted[w]
            // Batch together all wanted indices within the next BATCH frames.
            var lastW = w
            while (lastW + 1 < wanted.size && wanted[lastW + 1] - first < BATCH) lastW++
            val n = wanted[lastW] - first + 1
            val batch = r.getFramesAtIndex(first, n, params)
            for (k in w..lastW) {
                val src = batch[wanted[k] - first]
                result[k] = if (k > w && wanted[k] == wanted[k - 1]) result[k - 1]
                else scaled(src, Bitmap.Config.RGB_565)
            }
            batch.forEach { b -> if (result.none { it === b }) b.recycle() }
            w = lastW + 1
            onProgress(w.toFloat() / wanted.size)
        }
        return result.map { it!! }
    }

    fun release() {
        file.delete()
    }

    companion object {
        const val MAX_SHORT_SIDE = 512
        private const val BATCH = 8

        /** Copies the uri to the cache (so we keep access) and inspects it. */
        suspend fun open(context: Context, uri: Uri): MediaSource = withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            val dir = File(context.cacheDir, "sources").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "src_${System.nanoTime()}")
            cr.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                ?: error("Can't open file")

            val head = ByteArray(16)
            val read = file.inputStream().use { it.read(head) }
            val mime = cr.getType(uri).orEmpty()
            val isGif = read >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "GIF8"
            val isVideo = !isGif && (mime.startsWith("video/") || looksLikeVideo(head, read))

            when {
                isGif -> {
                    val d = openGif(file)
                    var total = 0L
                    for (i in 0 until d.frameCount) total += fixGifDelay(d.getDelay(i))
                    val src = MediaSource(
                        file, if (d.frameCount > 1) MediaKind.GIF else MediaKind.IMAGE,
                        total, d.width, d.height,
                        if (total > 0) d.frameCount * 1000f / total else 10f,
                    )
                    d.clear()
                    src
                }
                isVideo -> {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(file.path)
                        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 512
                        var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 512
                        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                        val frames = if (Build.VERSION.SDK_INT >= 28)
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() else null
                        val fps = if (frames != null && dur > 0) frames * 1000f / dur else 30f
                        if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == null && dur == 0L) {
                            error("Unsupported video")
                        }
                        MediaSource(file, MediaKind.VIDEO, dur, w, h, fps)
                    } finally {
                        r.release()
                    }
                }
                else -> {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, o)
                    if (o.outWidth <= 0) error("Unsupported file type")
                    MediaSource(file, MediaKind.IMAGE, 0, o.outWidth, o.outHeight, 0f)
                }
            }
        }

        private fun looksLikeVideo(h: ByteArray, n: Int): Boolean {
            if (n < 12) return false
            val box = String(h, 4, 4, Charsets.US_ASCII)
            if (box == "ftyp") return true // mp4 / mov / 3gp
            // WebM / Matroska
            return h[0] == 0x1A.toByte() && h[1] == 0x45.toByte() && h[2] == 0xDF.toByte() && h[3] == 0xA3.toByte()
        }

        /** Browsers treat GIF delays of <= 10ms as 100ms; we do the same. */
        private fun fixGifDelay(d: Int): Int = if (d <= 10) 100 else d

        private fun openGif(file: File): StandardGifDecoder {
            val decoder = StandardGifDecoder(SimpleBitmapProvider)
            decoder.setDefaultBitmapConfig(Bitmap.Config.ARGB_8888)
            val status = decoder.read(file.readBytes())
            if (status != GifDecoder.STATUS_OK || decoder.frameCount == 0) error("Can't decode GIF")
            return decoder
        }
    }

    private object SimpleBitmapProvider : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
            Bitmap.createBitmap(width, height, config)
        override fun release(bitmap: Bitmap) {}
        override fun obtainByteArray(size: Int) = ByteArray(size)
        override fun release(bytes: ByteArray) {}
        override fun obtainIntArray(size: Int) = IntArray(size)
        override fun release(array: IntArray) {}
    }
}
