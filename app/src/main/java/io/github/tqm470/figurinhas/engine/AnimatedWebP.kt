package io.github.tqm470.figurinhas.engine

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Builds animated WebP files without native code:
 * every frame is encoded with Android's own WebP encoder (Bitmap.compress), then the
 * resulting VP8/VP8L (+ALPH) chunks are wrapped into ANMF chunks of an animated RIFF container.
 * See https://developers.google.com/speed/webp/docs/riff_container
 */
object AnimatedWebP {

    /** The image-data chunks of one encoded frame (ALPH + VP8, or VP8L), with headers. */
    class EncodedFrame(val chunks: ByteArray, val hasAlpha: Boolean) {
        val size: Int get() = chunks.size
    }

    @Suppress("DEPRECATION")
    private val lossy: Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    /** Encodes a single static WebP file. */
    fun encodeStatic(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        bitmap.compress(lossy, quality.coerceIn(0, 99), out)
        return out.toByteArray()
    }

    fun encodeFrame(bitmap: Bitmap, quality: Int): EncodedFrame {
        val webp = encodeStatic(bitmap, quality)
        return extractImageChunks(webp)
    }

    /** Pulls the ALPH / VP8 / VP8L chunks out of a still WebP file. */
    fun extractImageChunks(webp: ByteArray): EncodedFrame {
        require(webp.size > 12 && fourCC(webp, 0) == "RIFF" && fourCC(webp, 8) == "WEBP") { "Not a WebP" }
        val out = ByteArrayOutputStream(webp.size)
        var hasAlpha = false
        var pos = 12
        while (pos + 8 <= webp.size) {
            val id = fourCC(webp, pos)
            val len = readLE32(webp, pos + 4)
            val padded = len + (len and 1)
            val end = minOf(pos + 8 + padded, webp.size)
            when (id) {
                "ALPH" -> { hasAlpha = true; out.write(webp, pos, end - pos) }
                "VP8 " -> out.write(webp, pos, end - pos)
                "VP8L" -> {
                    // bit 28 of the VP8L header (after the 0x2f signature byte) is "alpha_is_used"
                    if (len >= 5) {
                        val bits = readLE32(webp, pos + 9)
                        if ((bits ushr 28) and 1 == 1) hasAlpha = true
                    }
                    out.write(webp, pos, end - pos)
                }
            }
            pos += 8 + padded
        }
        val bytes = out.toByteArray()
        // Ensure an even length per chunk set (each chunk is already padded).
        return EncodedFrame(bytes, hasAlpha)
    }

    /**
     * Assembles an infinitely looping animated WebP.
     * @param frames encoded frames paired with their display duration in ms.
     */
    fun mux(frames: List<Pair<EncodedFrame, Int>>, width: Int, height: Int): ByteArray {
        require(frames.isNotEmpty())
        val body = ByteArrayOutputStream(frames.sumOf { it.first.size + 24 } + 64)
        val anyAlpha = frames.any { it.first.hasAlpha }

        // VP8X: extended header with the animation (+alpha) flags and canvas size.
        body.writeChunkHeader("VP8X", 10)
        body.write((if (anyAlpha) 0x10 else 0) or 0x02)
        body.write(0); body.write(0); body.write(0)
        body.writeLE24(width - 1)
        body.writeLE24(height - 1)

        // ANIM: transparent background, loop forever.
        body.writeChunkHeader("ANIM", 6)
        body.writeLE32(0)
        body.writeLE16(0)

        for ((frame, duration) in frames) {
            body.writeChunkHeader("ANMF", 16 + frame.size)
            body.writeLE24(0) // X / 2
            body.writeLE24(0) // Y / 2
            body.writeLE24(width - 1)
            body.writeLE24(height - 1)
            body.writeLE24(duration.coerceIn(MIN_FRAME_MS, 0xFFFFFF))
            // reserved(6) | blending: 1 = do not blend | disposal: 0 = none
            body.write(0x02)
            body.write(frame.chunks)
            if (frame.size and 1 == 1) body.write(0)
        }

        val payload = body.toByteArray()
        val file = ByteArrayOutputStream(payload.size + 12)
        file.write("RIFF".toByteArray(Charsets.US_ASCII))
        file.writeLE32(payload.size + 4)
        file.write("WEBP".toByteArray(Charsets.US_ASCII))
        file.write(payload)
        return file.toByteArray()
    }

    /** Header overhead of [mux] besides the frame data, used for size estimation. */
    fun overhead(frameCount: Int): Int = 12 + 18 + 14 + frameCount * 24

    const val MIN_FRAME_MS = 8

    private fun fourCC(b: ByteArray, off: Int) = String(b, off, 4, Charsets.US_ASCII)

    private fun readLE32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun ByteArrayOutputStream.writeChunkHeader(id: String, size: Int) {
        write(id.toByteArray(Charsets.US_ASCII))
        writeLE32(size)
    }

    private fun ByteArrayOutputStream.writeLE16(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLE24(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLE32(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v ushr 24) and 0xFF)
    }
}
