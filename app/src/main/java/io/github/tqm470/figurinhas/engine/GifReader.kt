package io.github.tqm470.figurinhas.engine

/**
 * Minimal, spec-compliant GIF89a decoder producing fully composited ARGB frames.
 * Handles local/global palettes, transparency, interlacing and all disposal methods
 * (keep / restore-to-background / restore-to-previous), with the background treated as
 * transparent, like browsers do.
 */
class GifReader(private val data: ByteArray) {

    class Frame(
        val x: Int, val y: Int, val w: Int, val h: Int,
        val delayMs: Int, val disposal: Int, val transparentIndex: Int,
        val interlaced: Boolean,
        val palette: IntArray,
        /** Offset of the LZW minimum code size byte. */
        val dataOffset: Int,
    )

    val width: Int
    val height: Int
    val frames: List<Frame>

    init {
        require(data.size >= 13 && String(data, 0, 3, Charsets.US_ASCII) == "GIF") { "Not a GIF" }
        width = u16(6)
        height = u16(8)
        val flags = u8(10)
        var pos = 13
        var global: IntArray? = null
        if (flags and 0x80 != 0) {
            val n = 2 shl (flags and 7)
            global = readPalette(pos, n)
            pos += 3 * n
        }
        val list = ArrayList<Frame>()
        var delay = 0
        var disposal = 0
        var transparent = -1
        loop@ while (pos < data.size) {
            when (u8(pos)) {
                0x21 -> { // extension
                    val label = u8(pos + 1)
                    pos += 2
                    if (label == 0xF9 && pos + 5 < data.size && u8(pos) >= 4) {
                        val p = u8(pos + 1)
                        disposal = (p shr 2) and 7
                        delay = u16(pos + 2) * 10
                        transparent = if (p and 1 != 0) u8(pos + 4) else -1
                    }
                    pos = skipBlocks(pos)
                }
                0x2C -> { // image descriptor
                    if (pos + 10 > data.size) break@loop
                    val x = u16(pos + 1); val y = u16(pos + 3)
                    val w = u16(pos + 5); val h = u16(pos + 7)
                    val f = u8(pos + 9)
                    pos += 10
                    var palette = global
                    if (f and 0x80 != 0) {
                        val n = 2 shl (f and 7)
                        palette = readPalette(pos, n)
                        pos += 3 * n
                    }
                    if (pos >= data.size) break@loop
                    list += Frame(x, y, w, h, delay, disposal, transparent, f and 0x40 != 0,
                        palette ?: IntArray(256) { 0xFF000000.toInt() }, pos)
                    pos = skipBlocks(pos + 1)
                    delay = 0; disposal = 0; transparent = -1
                }
                0x3B -> break@loop // trailer
                else -> break@loop // garbage; keep what we have
            }
        }
        frames = list
    }

    /** Decodes frames in order, calling [onFrame] with the full canvas (ARGB, reused between calls). */
    fun decode(onFrame: (index: Int, canvas: IntArray) -> Boolean) {
        val canvas = IntArray(width * height)
        var previous: IntArray? = null
        for ((i, f) in frames.withIndex()) {
            if (f.disposal == 3) previous = canvas.copyOf()
            drawFrame(f, canvas)
            if (!onFrame(i, canvas)) return
            when (f.disposal) {
                2 -> clearRect(canvas, f)
                3 -> previous?.copyInto(canvas)
            }
        }
    }

    private fun clearRect(canvas: IntArray, f: Frame) {
        for (yy in f.y until minOf(f.y + f.h, height)) {
            val row = yy * width
            for (xx in f.x until minOf(f.x + f.w, width)) canvas[row + xx] = 0
        }
    }

    private fun drawFrame(f: Frame, canvas: IntArray) {
        val pixels = lzw(f.dataOffset, f.w * f.h)
        val rows = if (f.interlaced) interlacedRows(f.h) else IntArray(f.h) { it }
        for (sy in 0 until f.h) {
            val yy = f.y + rows[sy]
            if (yy >= height) continue
            val row = yy * width
            val src = sy * f.w
            for (sx in 0 until f.w) {
                val xx = f.x + sx
                if (xx >= width) break
                val idx = pixels[src + sx].toInt() and 0xFF
                if (idx == f.transparentIndex) continue
                canvas[row + xx] = if (idx < f.palette.size) f.palette[idx] else 0xFF000000.toInt()
            }
        }
    }

    private fun interlacedRows(h: Int): IntArray {
        val out = IntArray(h)
        var i = 0
        for ((start, step) in arrayOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) {
            var r = start
            while (r < h) { out[i++] = r; r += step }
        }
        return out
    }

    /** Standard GIF LZW decoding of the sub-block chain starting at [offset]. */
    private fun lzw(offset: Int, count: Int): ByteArray {
        val out = ByteArray(count)
        val minCode = u8(offset).coerceIn(2, 8)
        val clear = 1 shl minCode
        val eoi = clear + 1
        val prefix = ShortArray(4096)
        val suffix = ByteArray(4096)
        val stack = ByteArray(4097)
        for (i in 0 until clear) suffix[i] = i.toByte()

        var codeSize = minCode + 1
        var codeMask = (1 shl codeSize) - 1
        var available = clear + 2
        var oldCode = -1
        var first = 0
        var bits = 0
        var datum = 0
        var outPos = 0
        var pos = offset + 1
        var blockLeft = 0

        outer@ while (outPos < count) {
            while (bits < codeSize) {
                if (blockLeft == 0) {
                    if (pos >= data.size) break@outer
                    blockLeft = u8(pos++)
                    if (blockLeft == 0) break@outer
                }
                if (pos >= data.size) break@outer
                datum = datum or (u8(pos++) shl bits)
                bits += 8
                blockLeft--
            }
            val code = datum and codeMask
            datum = datum ushr codeSize
            bits -= codeSize

            if (code == clear) {
                codeSize = minCode + 1
                codeMask = (1 shl codeSize) - 1
                available = clear + 2
                oldCode = -1
                continue
            }
            if (code == eoi) break
            if (oldCode == -1) {
                if (code >= clear) continue
                out[outPos++] = suffix[code]
                oldCode = code
                first = code
                continue
            }
            var top = 0
            var c = code
            if (code >= available) {
                stack[top++] = first.toByte()
                c = oldCode
            }
            while (c >= clear) {
                if (c >= 4096 || top >= 4096) break@outer
                stack[top++] = suffix[c]
                c = prefix[c].toInt()
            }
            first = suffix[c].toInt() and 0xFF
            stack[top++] = first.toByte()
            if (available < 4096) {
                prefix[available] = oldCode.toShort()
                suffix[available] = first.toByte()
                available++
                if (available and codeMask == 0 && available < 4096) {
                    codeSize++
                    codeMask = (1 shl codeSize) - 1
                }
            }
            oldCode = code
            while (top > 0 && outPos < count) out[outPos++] = stack[--top]
        }
        return out
    }

    private fun readPalette(pos: Int, n: Int): IntArray = IntArray(n) { i ->
        val p = pos + 3 * i
        if (p + 2 < data.size) {
            (0xFF shl 24) or (u8(p) shl 16) or (u8(p + 1) shl 8) or u8(p + 2)
        } else 0xFF000000.toInt()
    }

    private fun skipBlocks(start: Int): Int {
        var pos = start
        while (pos < data.size) {
            val len = u8(pos)
            pos += 1 + len
            if (len == 0) break
        }
        return pos
    }

    private fun u8(p: Int) = data[p].toInt() and 0xFF
    private fun u16(p: Int) = u8(p) or (u8(p + 1) shl 8)
}
