package io.github.tqm470.figurinhas.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.tqm470.figurinhas.BuildConfig
import io.github.tqm470.figurinhas.data.Pack
import io.github.tqm470.figurinhas.data.StickerStore

/**
 * Implements the contract WhatsApp uses to read third-party sticker packs
 * (same URIs/columns as WhatsApp's official sample app).
 */
class StickerContentProvider : ContentProvider() {

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        val a = BuildConfig.STICKER_AUTHORITY
        addURI(a, METADATA, ALL_PACKS)
        addURI(a, "$METADATA/*", ONE_PACK)
        addURI(a, "$STICKERS/*", STICKERS_OF_PACK)
        addURI(a, "$STICKERS_ASSET/*/*", STICKER_FILE)
    }

    private val store get() = StickerStore.get(context!!)

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = when (matcher.match(uri)) {
        ALL_PACKS -> packCursor(uri, store.packs.value)
        ONE_PACK -> packCursor(uri, listOfNotNull(store.pack(uri.lastPathSegment.orEmpty())))
        STICKERS_OF_PACK -> {
            val cursor = MatrixCursor(arrayOf(STICKER_FILE_NAME, STICKER_EMOJI, STICKER_ACCESSIBILITY_TEXT))
            store.pack(uri.lastPathSegment.orEmpty())?.stickers?.forEach {
                cursor.addRow(arrayOf<Any>(it.file, it.emojis.joinToString(","), ""))
            }
            cursor.setNotificationUri(context!!.contentResolver, uri)
            cursor
        }
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    private fun packCursor(uri: Uri, packs: List<Pack>): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                PACK_IDENTIFIER, PACK_NAME, PACK_PUBLISHER, PACK_ICON,
                ANDROID_APP_LINK, IOS_APP_LINK, PUBLISHER_EMAIL, PUBLISHER_WEBSITE,
                PRIVACY_POLICY, LICENSE_AGREEMENT, IMAGE_DATA_VERSION, AVOID_CACHE, ANIMATED_PACK,
            )
        )
        packs.filter { it.stickers.isNotEmpty() }.forEach { p ->
            cursor.addRow(
                arrayOf<Any>(
                    p.id, p.name, p.publisher.ifBlank { "Figurinhas" }, Pack.TRAY_FILE,
                    "", "", "", "", "", "",
                    p.version.toString(), 0, if (p.animated) 1 else 0,
                )
            )
        }
        cursor.setNotificationUri(context!!.contentResolver, uri)
        return cursor
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != STICKER_FILE) return null
        val segs = uri.pathSegments
        if (segs.size != 3) return null
        val file = store.file(segs[1], segs[2]) ?: return null
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        ALL_PACKS -> "vnd.android.cursor.dir/vnd.${BuildConfig.STICKER_AUTHORITY}.$METADATA"
        ONE_PACK -> "vnd.android.cursor.item/vnd.${BuildConfig.STICKER_AUTHORITY}.$METADATA"
        STICKERS_OF_PACK -> "vnd.android.cursor.dir/vnd.${BuildConfig.STICKER_AUTHORITY}.$STICKERS"
        STICKER_FILE -> if (uri.lastPathSegment.orEmpty().endsWith(".png")) "image/png" else "image/webp"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        const val METADATA = "metadata"
        const val STICKERS = "stickers"
        const val STICKERS_ASSET = "stickers_asset"

        private const val ALL_PACKS = 1
        private const val ONE_PACK = 2
        private const val STICKERS_OF_PACK = 3
        private const val STICKER_FILE = 4

        const val PACK_IDENTIFIER = "sticker_pack_identifier"
        const val PACK_NAME = "sticker_pack_name"
        const val PACK_PUBLISHER = "sticker_pack_publisher"
        const val PACK_ICON = "sticker_pack_icon"
        const val ANDROID_APP_LINK = "android_play_store_link"
        const val IOS_APP_LINK = "ios_app_download_link"
        const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        const val PRIVACY_POLICY = "sticker_pack_privacy_policy_website"
        const val LICENSE_AGREEMENT = "sticker_pack_license_agreement_website"
        const val IMAGE_DATA_VERSION = "image_data_version"
        const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        const val ANIMATED_PACK = "animated_sticker_pack"

        const val STICKER_FILE_NAME = "sticker_file_name"
        const val STICKER_EMOJI = "sticker_emoji"
        const val STICKER_ACCESSIBILITY_TEXT = "sticker_accessibility_text"
    }
}
