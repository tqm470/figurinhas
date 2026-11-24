package io.github.tqm470.figurinhas.whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import io.github.tqm470.figurinhas.BuildConfig
import io.github.tqm470.figurinhas.data.Pack

object WhatsApp {
    const val CONSUMER = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"

    fun installed(context: Context): List<String> =
        listOf(CONSUMER, BUSINESS).filter { isInstalled(context, it) }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Intent that opens WhatsApp's "Add sticker pack" dialog. */
    fun addPackIntent(pack: Pack, targetPackage: String?): Intent =
        Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
            putExtra("sticker_pack_id", pack.id)
            putExtra("sticker_pack_authority", BuildConfig.STICKER_AUTHORITY)
            putExtra("sticker_pack_name", pack.name)
            if (targetPackage != null) setPackage(targetPackage)
        }

    /**
     * Asks WhatsApp whether the pack is already added (same check WhatsApp's sample app uses).
     * Returns true if it's added in every installed WhatsApp.
     */
    fun isPackAdded(context: Context, packId: String): Boolean {
        val apps = installed(context)
        if (apps.isEmpty()) return false
        return apps.all { pkg ->
            val authority = "$pkg.provider.sticker_whitelist_check"
            val uri = Uri.Builder().scheme("content").authority(authority)
                .appendPath("is_whitelisted")
                .appendQueryParameter("authority", BuildConfig.STICKER_AUTHORITY)
                .appendQueryParameter("identifier", packId)
                .build()
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow("result")) == 1
                } ?: false
            }.getOrDefault(false)
        }
    }
}
