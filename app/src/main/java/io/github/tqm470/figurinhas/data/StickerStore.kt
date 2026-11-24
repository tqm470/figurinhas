package io.github.tqm470.figurinhas.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Sticker(
    val file: String,
    val emojis: List<String>,
) {
    /** Small PNG thumbnail of the first frame, also used to build the tray icon. */
    val thumb: String get() = file.substringBeforeLast('.') + "_thumb.png"
}

data class Pack(
    val id: String,
    val name: String,
    val publisher: String,
    val animated: Boolean,
    val stickers: List<Sticker>,
    /** Bumped on every change so WhatsApp knows to re-fetch the images. */
    val version: Int,
    /** File name of the sticker whose thumbnail is used as the tray icon, or null for the first. */
    val trayFrom: String?,
    val createdAt: Long,
) {
    val canAddToWhatsApp: Boolean get() = stickers.size in MIN_STICKERS..MAX_STICKERS
    val isFull: Boolean get() = stickers.size >= MAX_STICKERS

    companion object {
        const val MIN_STICKERS = 3
        const val MAX_STICKERS = 30
        const val TRAY_FILE = "tray.png"
    }
}

/**
 * Stores packs under filesDir/packs/<id>/ as a pack.json plus the sticker files.
 * Used by both the UI and the ContentProvider that WhatsApp reads.
 */
class StickerStore private constructor(context: Context) {

    private val root = File(context.filesDir, "packs").apply { mkdirs() }
    private val _packs = MutableStateFlow(loadAll())
    val packs: StateFlow<List<Pack>> = _packs.asStateFlow()

    fun pack(id: String): Pack? = _packs.value.firstOrNull { it.id == id }

    fun packDir(id: String) = File(root, id)

    fun file(packId: String, name: String): File? {
        // Never allow escaping the pack directory.
        if (name.contains('/') || name.contains("..")) return null
        val f = File(packDir(packId), name)
        return if (f.isFile) f else null
    }

    @Synchronized
    fun createPack(name: String, publisher: String, animated: Boolean): Pack {
        val id = "pack_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val pack = Pack(
            id = id,
            name = name.trim().take(128),
            publisher = publisher.trim().take(128),
            animated = animated,
            stickers = emptyList(),
            version = 1,
            trayFrom = null,
            createdAt = System.currentTimeMillis(),
        )
        packDir(id).mkdirs()
        save(pack)
        return pack
    }

    @Synchronized
    fun updateInfo(id: String, name: String, publisher: String) {
        val p = pack(id) ?: return
        save(p.copy(name = name.trim().take(128), publisher = publisher.trim().take(128), version = p.version + 1))
    }

    @Synchronized
    fun deletePack(id: String) {
        packDir(id).deleteRecursively()
        _packs.value = _packs.value.filterNot { it.id == id }
    }

    /** Adds an already-encoded sticker (512x512 WebP) to the pack. */
    @Synchronized
    fun addSticker(packId: String, webp: ByteArray, firstFrame: Bitmap, emojis: List<String>): Pack {
        val p = pack(packId) ?: error("Pack not found")
        check(!p.isFull) { "Pack is full" }
        val dir = packDir(packId)
        // Unique, never-reused names so image caches (ours and WhatsApp's) never show stale files.
        var n = System.currentTimeMillis()
        while (File(dir, "s$n.webp").exists()) n++
        val sticker = Sticker("s$n.webp", emojis.ifEmpty { listOf(DEFAULT_EMOJI) })
        File(dir, sticker.file).writeBytes(webp)
        File(dir, sticker.thumb).outputStream().use {
            Bitmap.createScaledBitmap(firstFrame, TRAY_SIZE, TRAY_SIZE, true)
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val updated = p.copy(stickers = p.stickers + sticker, version = p.version + 1)
        writeTray(updated)
        save(updated)
        return updated
    }

    @Synchronized
    fun removeSticker(packId: String, file: String) {
        val p = pack(packId) ?: return
        val s = p.stickers.firstOrNull { it.file == file } ?: return
        File(packDir(packId), s.file).delete()
        File(packDir(packId), s.thumb).delete()
        val updated = p.copy(
            stickers = p.stickers - s,
            version = p.version + 1,
            trayFrom = p.trayFrom.takeIf { it != file },
        )
        writeTray(updated)
        save(updated)
    }

    @Synchronized
    fun setEmojis(packId: String, file: String, emojis: List<String>) {
        val p = pack(packId) ?: return
        val stickers = p.stickers.map { if (it.file == file) it.copy(emojis = emojis.ifEmpty { listOf(DEFAULT_EMOJI) }) else it }
        save(p.copy(stickers = stickers, version = p.version + 1))
    }

    @Synchronized
    fun setTray(packId: String, file: String) {
        val p = pack(packId) ?: return
        val updated = p.copy(trayFrom = file, version = p.version + 1)
        writeTray(updated)
        save(updated)
    }

    @Synchronized
    fun move(packId: String, file: String, delta: Int) {
        val p = pack(packId) ?: return
        val list = p.stickers.toMutableList()
        val i = list.indexOfFirst { it.file == file }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        val updated = p.copy(stickers = list, version = p.version + 1)
        writeTray(updated)
        save(updated)
    }

    private fun writeTray(p: Pack) {
        val dir = packDir(p.id)
        val trayFile = File(dir, Pack.TRAY_FILE)
        val source = p.stickers.firstOrNull { it.file == p.trayFrom } ?: p.stickers.firstOrNull()
        if (source == null) {
            trayFile.delete()
            return
        }
        val thumb = File(dir, source.thumb)
        if (thumb.isFile) {
            // Thumbs are 96x96 PNGs, well under WhatsApp's 50 KB tray limit.
            thumb.copyTo(trayFile, overwrite = true)
        } else {
            BitmapFactory.decodeFile(File(dir, source.file).path)?.let { bmp ->
                trayFile.outputStream().use {
                    Bitmap.createScaledBitmap(bmp, TRAY_SIZE, TRAY_SIZE, true)
                        .compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }

    private fun save(p: Pack) {
        val json = JSONObject()
            .put("id", p.id)
            .put("name", p.name)
            .put("publisher", p.publisher)
            .put("animated", p.animated)
            .put("version", p.version)
            .put("trayFrom", p.trayFrom ?: JSONObject.NULL)
            .put("createdAt", p.createdAt)
            .put("stickers", JSONArray().apply {
                p.stickers.forEach { s ->
                    put(JSONObject().put("file", s.file).put("emojis", JSONArray(s.emojis)))
                }
            })
        val dir = packDir(p.id).apply { mkdirs() }
        val tmp = File(dir, "pack.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(File(dir, "pack.json"))
        val others = _packs.value.filterNot { it.id == p.id }
        _packs.value = (others + p).sortedBy { it.createdAt }
    }

    private fun loadAll(): List<Pack> =
        root.listFiles().orEmpty().mapNotNull { dir ->
            runCatching {
                val o = JSONObject(File(dir, "pack.json").readText())
                val arr = o.getJSONArray("stickers")
                Pack(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    publisher = o.getString("publisher"),
                    animated = o.getBoolean("animated"),
                    version = o.getInt("version"),
                    trayFrom = if (o.isNull("trayFrom")) null else o.getString("trayFrom"),
                    createdAt = o.optLong("createdAt"),
                    stickers = (0 until arr.length()).map { i ->
                        val s = arr.getJSONObject(i)
                        val e = s.getJSONArray("emojis")
                        Sticker(s.getString("file"), (0 until e.length()).map { e.getString(it) })
                    },
                )
            }.getOrNull()
        }.sortedBy { it.createdAt }

    companion object {
        const val TRAY_SIZE = 96
        const val DEFAULT_EMOJI = "😀"

        @Volatile private var instance: StickerStore? = null

        fun get(context: Context): StickerStore =
            instance ?: synchronized(this) {
                instance ?: StickerStore(context.applicationContext).also { instance = it }
            }
    }
}
