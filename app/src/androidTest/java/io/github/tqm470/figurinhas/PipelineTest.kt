package io.github.tqm470.figurinhas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tqm470.figurinhas.data.StickerStore
import io.github.tqm470.figurinhas.engine.FitMode
import io.github.tqm470.figurinhas.engine.MediaKind
import io.github.tqm470.figurinhas.engine.MediaSource
import io.github.tqm470.figurinhas.engine.RenderOptions
import io.github.tqm470.figurinhas.engine.StickerMaker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end check of the sticker pipeline on a real device/emulator.
 * Generates its own inputs, so it needs no fixtures.
 */
@RunWith(AndroidJUnit4::class)
class PipelineTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun webpInfo(bytes: ByteArray): Triple<Int, Int, Boolean> {
        val f = File(ctx.cacheDir, "check.webp").apply { writeBytes(bytes) }
        val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f))
        return Triple(d.intrinsicWidth, d.intrinsicHeight, d is AnimatedImageDrawable)
    }

    private fun photoUri(): Uri {
        val bmp = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(0xFF3366CC.toInt())
        val p = android.graphics.Paint().apply { color = 0xFFFFDD00.toInt() }
        c.drawCircle(600f, 400f, 300f, p)
        val f = File(ctx.cacheDir, "photo.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f)
    }

    private fun input(name: String): Uri? {
        val f = File(ctx.filesDir, "in/$name")
        return if (f.isFile) Uri.fromFile(f) else null
    }

    @Test
    fun photoBecomesStaticAndAnimatedSticker() = runBlocking {
        val src = MediaSource.open(ctx, photoUri())
        assertEquals(MediaKind.IMAGE, src.kind)
        val frames = src.frames(0, 1, 100.0)
        val opts = RenderOptions(fit = FitMode.FILL, text = "Bom dia!", rounded = true)

        val st = StickerMaker.makeStatic(frames[0], opts)
        assertTrue("static ${st.webp.size}", st.webp.size <= StickerMaker.STATIC_LIMIT)
        val (w, h, anim) = webpInfo(st.webp)
        assertEquals(512, w); assertEquals(512, h); assertEquals(false, anim)

        val an = StickerMaker.makeAnimated(frames, 100, opts) {}
        assertTrue(an.frameCount >= 2)
        val (w2, _, anim2) = webpInfo(an.webp)
        assertEquals(512, w2); assertTrue(anim2)
    }

    @Test
    fun gifAndVideoFitUnderWhatsAppLimit() = runBlocking {
        for (name in listOf("test.gif", "video.mp4")) {
            val uri = input(name) ?: continue
            val src = MediaSource.open(ctx, uri)
            val end = minOf(src.durationMs, 10_000L)
            val t0 = System.currentTimeMillis()
            val frames = src.frames(0, end, 100.0)
            val t1 = System.currentTimeMillis()
            val r = StickerMaker.makeAnimated(frames, 100, RenderOptions(text = "kkkkk")) {}
            val t2 = System.currentTimeMillis()
            android.util.Log.i("PipelineTest", "$name kind=${src.kind} dur=${src.durationMs} frames=${frames.size} " +
                "decode=${t1 - t0}ms encode=${t2 - t1}ms size=${r.webp.size} q=${r.quality} out=${r.frameCount}f/${r.durationMs}ms")
            assertTrue(r.webp.size <= StickerMaker.ANIMATED_LIMIT)
            assertTrue(r.durationMs <= 10_000)
            val (w, h, anim) = webpInfo(r.webp)
            assertEquals(512, w); assertEquals(512, h); assertTrue(anim)
            File(ctx.filesDir, "out_$name.webp").writeBytes(r.webp)
        }
    }

    @Test
    fun packIsServedThroughProvider() = runBlocking {
        val store = StickerStore.get(ctx)
        val pack = store.createPack("Test pack", "Me", animated = true)
        val src = MediaSource.open(ctx, photoUri())
        val frames = src.frames(0, 1, 100.0)
        repeat(3) {
            val r = StickerMaker.makeAnimated(frames, 100, RenderOptions(text = "#$it")) {}
            store.addSticker(pack.id, r.webp, r.firstFrame, listOf("😀"))
        }
        val p = store.pack(pack.id)!!
        assertTrue(p.canAddToWhatsApp)

        val auth = BuildConfig.STICKER_AUTHORITY
        val cr = ctx.contentResolver
        cr.query(Uri.parse("content://$auth/metadata/${p.id}"), null, null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Test pack", c.getString(c.getColumnIndexOrThrow("sticker_pack_name")))
            assertEquals(1, c.getInt(c.getColumnIndexOrThrow("animated_sticker_pack")))
            assertEquals("tray.png", c.getString(c.getColumnIndexOrThrow("sticker_pack_icon")))
        }
        val files = ArrayList<String>()
        cr.query(Uri.parse("content://$auth/stickers/${p.id}"), null, null, null, null)!!.use { c ->
            while (c.moveToNext()) files += c.getString(c.getColumnIndexOrThrow("sticker_file_name"))
        }
        assertEquals(3, files.size)
        for (f in files + "tray.png") {
            cr.openAssetFileDescriptor(Uri.parse("content://$auth/stickers_asset/${p.id}/$f"), "r")!!.use { afd ->
                assertTrue(afd.length > 0)
            }
        }
        val tray = cr.openInputStream(Uri.parse("content://$auth/stickers_asset/${p.id}/tray.png"))!!.use { it.readBytes() }
        assertTrue(tray.size < 50 * 1024)
        val o = BitmapFactory.decodeByteArray(tray, 0, tray.size)
        assertEquals(96, o.width)
        store.deletePack(p.id)
    }
}
