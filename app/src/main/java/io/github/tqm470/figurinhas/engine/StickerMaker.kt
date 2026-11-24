package io.github.tqm470.figurinhas.engine

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

/**
 * Turns frames into a WhatsApp-compliant sticker:
 *  - 512x512 WebP
 *  - animated: <= 500 KB, <= 10 s, >= 2 frames, each >= 8 ms
 *  - static:   <= 100 KB
 * It searches quality / frame rate automatically so the user never has to.
 */
object StickerMaker {
    const val ANIMATED_LIMIT = 500 * 1024
    const val STATIC_LIMIT = 100 * 1024
    const val MAX_DURATION_MS = 10_000

    private val QUALITIES = intArrayOf(85, 75, 65, 55, 45, 36, 28, 20, 13, 8)

    class Result(val webp: ByteArray, val firstFrame: Bitmap, val quality: Int, val frameCount: Int, val durationMs: Int)

    class TooBigException : Exception("Couldn't fit the sticker under WhatsApp's size limit. Try a shorter clip or lower FPS.")

    /** A source bitmap shown for a given time. */
    private class Slot(val src: Bitmap, var durationMs: Int)

    suspend fun makeStatic(frame: Bitmap, opts: RenderOptions): Result = withContext(Dispatchers.Default) {
        val rendered = StickerRenderer.render(frame, opts)
        for (q in QUALITIES) {
            val bytes = AnimatedWebP.encodeStatic(rendered, q)
            if (bytes.size <= STATIC_LIMIT) return@withContext Result(bytes, rendered, q, 1, 0)
        }
        throw TooBigException()
    }

    suspend fun makeAnimated(
        frames: List<Bitmap>,
        frameDurationMs: Int,
        opts: RenderOptions,
        onProgress: (Float) -> Unit,
    ): Result = withContext(Dispatchers.Default) {
        require(frames.isNotEmpty())
        var slots = mergeIdentical(frames, frameDurationMs)
        slots = clampDuration(slots)
        if (slots.size == 1) {
            // WhatsApp only treats a sticker as animated if it has more than one frame.
            val d = slots[0].durationMs
            slots = listOf(Slot(slots[0].src, (d / 2).coerceAtLeast(500)), Slot(slots[0].src, (d / 2).coerceAtLeast(500)))
        }

        val canvas = Bitmap.createBitmap(StickerRenderer.SIZE, StickerRenderer.SIZE, Bitmap.Config.ARGB_8888)
        val firstFrame = StickerRenderer.render(slots[0].src, opts)

        // 1) Estimate bytes/frame per quality from a few sample frames.
        val sampleIdx = (0 until minOf(6, slots.size)).map { it * (slots.size - 1) / maxOf(1, minOf(6, slots.size) - 1) }.distinct()
        val avgSize = IntArray(QUALITIES.size)
        QUALITIES.forEachIndexed { qi, q ->
            coroutineContext.ensureActive()
            avgSize[qi] = sampleIdx.sumOf {
                AnimatedWebP.encodeFrame(StickerRenderer.render(slots[it].src, opts, canvas), q).size
            } / sampleIdx.size
            onProgress(0.15f * (qi + 1) / QUALITIES.size)
        }

        // 2) Pick the best (quality, frame skip) pair predicted to fit, then verify with a real encode.
        var step = 1
        var qi = choose(avgSize, slots.size, step)
        while (qi < 0 && ceil(slots.size / (step + 1.0)) >= 2) {
            step++
            qi = choose(avgSize, slots.size, step)
        }
        if (qi < 0) qi = QUALITIES.lastIndex

        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            val selected = decimate(slots, step)
            val encoded = ArrayList<Pair<AnimatedWebP.EncodedFrame, Int>>(selected.size)
            val cache = HashMap<Bitmap, AnimatedWebP.EncodedFrame>()
            var running = AnimatedWebP.overhead(selected.size)
            var overflow = false
            for ((i, s) in selected.withIndex()) {
                coroutineContext.ensureActive()
                val f = cache.getOrPut(s.src) {
                    AnimatedWebP.encodeFrame(StickerRenderer.render(s.src, opts, canvas), QUALITIES[qi])
                }
                encoded += f to s.durationMs
                running += f.size + 24
                if (running > ANIMATED_LIMIT) { overflow = true; break }
                onProgress(0.15f + 0.85f * (i + 1) / selected.size)
            }
            if (!overflow) {
                val bytes = AnimatedWebP.mux(encoded, StickerRenderer.SIZE, StickerRenderer.SIZE)
                if (bytes.size <= ANIMATED_LIMIT) {
                    onProgress(1f)
                    return@withContext Result(bytes, firstFrame, QUALITIES[qi], selected.size, selected.sumOf { it.durationMs })
                }
            }
            // Didn't fit: lower quality, or drop frames once quality is already very low.
            attempt++
            if (qi < QUALITIES.lastIndex && (QUALITIES[qi] > 28 || ceil(slots.size / (step + 1.0)) < 2)) {
                qi++
            } else if (ceil(slots.size / (step + 1.0)) >= 2) {
                step++
                qi = maxOf(0, choose(avgSize, slots.size, step)).coerceAtLeast(qi - 2)
            } else {
                throw TooBigException()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw TooBigException()
    }

    private fun choose(avgSize: IntArray, slotCount: Int, step: Int): Int {
        val count = ceil(slotCount / step.toDouble()).toInt()
        for (qi in QUALITIES.indices) {
            val est = AnimatedWebP.overhead(count) + count.toLong() * (avgSize[qi] + 24)
            if (est <= ANIMATED_LIMIT * 0.9) return qi
        }
        return -1
    }

    /** Keeps every [step]th slot, giving it the duration of the slots it replaces. */
    private fun decimate(slots: List<Slot>, step: Int): List<Slot> {
        if (step <= 1) return slots
        return slots.chunked(step).map { group -> Slot(group[0].src, group.sumOf { it.durationMs }) }
    }

    private fun mergeIdentical(frames: List<Bitmap>, d: Int): List<Slot> {
        val out = ArrayList<Slot>()
        for (f in frames) {
            val last = out.lastOrNull()
            if (last != null && last.src === f) last.durationMs += d
            else out += Slot(f, d)
        }
        return out
    }

    private fun clampDuration(slots: List<Slot>): List<Slot> {
        val out = ArrayList<Slot>()
        var total = 0
        for (s in slots) {
            if (MAX_DURATION_MS - total < AnimatedWebP.MIN_FRAME_MS) break
            val d = minOf(s.durationMs, MAX_DURATION_MS - total).coerceAtLeast(AnimatedWebP.MIN_FRAME_MS)
            out += Slot(s.src, d)
            total += d
        }
        return out
    }
}
