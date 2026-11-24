package io.github.tqm470.figurinhas.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnimatedWebPTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("frames/$name")!!.readBytes()

    @Test
    fun extractsAlphaAndImageChunks() {
        val f = AnimatedWebP.extractImageChunks(fixture("alpha0.webp"))
        assertTrue(f.hasAlpha)
        assertEquals("ALPH", String(f.chunks, 0, 4))
        val o = AnimatedWebP.extractImageChunks(fixture("opaque0.webp"))
        assertEquals(false, o.hasAlpha)
        assertEquals("VP8 ", String(o.chunks, 0, 4))
        val l = AnimatedWebP.extractImageChunks(fixture("lossless.webp"))
        assertTrue(l.hasAlpha)
        assertEquals("VP8L", String(l.chunks, 0, 4))
    }

    @Test
    fun muxesValidAnimation() {
        val names = listOf("alpha0.webp", "alpha1.webp", "alpha2.webp", "opaque0.webp", "opaque1.webp", "lossless.webp")
        val frames = names.mapIndexed { i, n -> AnimatedWebP.extractImageChunks(fixture(n)) to (100 + i * 10) }
        val out = AnimatedWebP.mux(frames, 64, 64)
        assertEquals("RIFF", String(out, 0, 4))
        val riffSize = (out[4].toInt() and 0xFF) or ((out[5].toInt() and 0xFF) shl 8) or
            ((out[6].toInt() and 0xFF) shl 16) or ((out[7].toInt() and 0xFF) shl 24)
        assertEquals(out.size - 8, riffSize)
        // Written for external validation (Pillow / libwebp) by the dev workflow.
        File(System.getProperty("java.io.tmpdir"), "figurinhas-test-anim.webp").writeBytes(out)
    }
}
