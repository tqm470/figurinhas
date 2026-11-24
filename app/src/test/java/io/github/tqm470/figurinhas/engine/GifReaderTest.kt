package io.github.tqm470.figurinhas.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class GifReaderTest {
    private val bytes = javaClass.classLoader!!.getResourceAsStream("disposal.gif")!!.readBytes()

    @Test
    fun parsesFramesAndDelays() {
        val g = GifReader(bytes)
        assertEquals(480, g.width)
        assertEquals(270, g.height)
        assertEquals(40, g.frames.size)
        assertEquals(50, g.frames[3].delayMs)
    }

    @Test
    fun restoreToBackgroundLeavesNoTrails() {
        val g = GifReader(bytes)
        val dump = File(System.getProperty("java.io.tmpdir"), "figurinhas-gif-frames.raw").outputStream()
        g.decode { i, canvas ->
            if (i == 10) {
                // The ball started at (390,135); by frame 10 that spot must be transparent again.
                assertEquals(0, canvas[135 * g.width + 390] ushr 24)
            }
            val buf = java.nio.ByteBuffer.allocate(canvas.size * 4)
            canvas.forEach { buf.putInt(it) }
            dump.write(buf.array())
            true
        }
        dump.close()
    }
}
