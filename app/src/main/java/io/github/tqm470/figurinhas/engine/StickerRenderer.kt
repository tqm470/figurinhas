package io.github.tqm470.figurinhas.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min

enum class FitMode { FIT, FILL }
enum class TextPosition { TOP, BOTTOM }

data class RenderOptions(
    val fit: FitMode = FitMode.FIT,
    /** Extra zoom on top of fit/fill, 1..4. */
    val zoom: Float = 1f,
    /** -1..1, which part to keep when the image overflows the square. */
    val panX: Float = 0f,
    val panY: Float = 0f,
    val rounded: Boolean = false,
    val text: String = "",
    val textColor: Int = Color.WHITE,
    val textPosition: TextPosition = TextPosition.BOTTOM,
)

/** Draws a source frame onto the 512x512 sticker canvas. */
object StickerRenderer {
    const val SIZE = 512
    /** WhatsApp guidelines recommend a small transparent margin around stickers. */
    private const val MARGIN = 16f

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textCache = HashMap<Triple<String, Int, TextPosition>, StaticLayout>()

    fun render(src: Bitmap, o: RenderOptions, reuse: Bitmap? = null): Bitmap {
        val out = reuse?.takeIf { it.width == SIZE && it.height == SIZE && it.isMutable }
            ?: Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.TRANSPARENT)
        draw(Canvas(out), src, o)
        return out
    }

    /** How far (in 512-space px) the scaled image overflows the canvas horizontally / vertically. */
    fun overflow(srcW: Int, srcH: Int, o: RenderOptions): Pair<Float, Float> {
        val area = if (o.fit == FitMode.FIT) SIZE - 2 * MARGIN else SIZE.toFloat()
        val base = if (o.fit == FitMode.FIT) min(area / srcW, area / srcH) else max(area / srcW, area / srcH)
        val scale = base * o.zoom.coerceIn(1f, 4f)
        return max(0f, srcW * scale - SIZE) to max(0f, srcH * scale - SIZE)
    }

    /** Draws in a 512x512 coordinate space (callers may pre-scale the canvas). */
    fun draw(canvas: Canvas, src: Bitmap, o: RenderOptions) {
        val area = if (o.fit == FitMode.FIT) SIZE - 2 * MARGIN else SIZE.toFloat()
        val base = if (o.fit == FitMode.FIT) min(area / src.width, area / src.height)
        else max(area / src.width, area / src.height)
        val scale = base * o.zoom.coerceIn(1f, 4f)
        val w = src.width * scale
        val h = src.height * scale
        val overflowX = max(0f, w - SIZE)
        val overflowY = max(0f, h - SIZE)
        val left = (SIZE - w) / 2f - o.panX.coerceIn(-1f, 1f) * overflowX / 2f
        val top = (SIZE - h) / 2f - o.panY.coerceIn(-1f, 1f) * overflowY / 2f
        val dst = RectF(left, top, left + w, top + h)

        canvas.save()
        canvas.clipRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat())
        if (o.rounded) {
            val visible = RectF(max(0f, dst.left), max(0f, dst.top), min(SIZE.toFloat(), dst.right), min(SIZE.toFloat(), dst.bottom))
            val r = min(visible.width(), visible.height()) * 0.14f
            canvas.clipPath(Path().apply { addRoundRect(visible, r, r, Path.Direction.CW) })
        }
        canvas.drawBitmap(src, null, dst, bitmapPaint)
        canvas.restore()

        if (o.text.isNotBlank()) drawText(canvas, o)
    }

    private fun drawText(canvas: Canvas, o: RenderOptions) {
        val key = Triple(o.text.trim(), o.textColor, o.textPosition)
        val layout = synchronized(textCache) {
            textCache.getOrPut(key) { buildLayout(key.first, o.textColor) }.also {
                if (textCache.size > 32) textCache.clear()
            }
        }
        // Draw outline pass then fill pass with the same layout.
        val paint = layout.paint
        val x = (SIZE - layout.width) / 2f
        val y = when (o.textPosition) {
            TextPosition.TOP -> MARGIN + 4f
            TextPosition.BOTTOM -> SIZE - MARGIN - 4f - layout.height
        }
        canvas.save()
        canvas.translate(x, y)
        synchronized(layout) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = paint.textSize * 0.2f
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = if (isDark(o.textColor)) Color.WHITE else Color.BLACK
            layout.draw(canvas)
            paint.style = Paint.Style.FILL
            paint.color = o.textColor
            layout.draw(canvas)
        }
        canvas.restore()
    }

    private fun buildLayout(text: String, color: Int): StaticLayout {
        val width = (SIZE - 2 * MARGIN - 8).toInt()
        var size = 84f
        while (true) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                this.color = color
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 0.92f)
                .setIncludePad(false)
                .build()
            val longestWord = text.split(Regex("\\s+")).maxOfOrNull { paint.measureText(it) } ?: 0f
            if ((layout.lineCount <= 3 && layout.height <= SIZE * 0.42f && longestWord <= width) || size <= 22f) {
                return layout
            }
            size -= 4f
        }
    }

    private fun isDark(c: Int): Boolean =
        (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 < 80
}
