package com.fileapex.update

import android.content.Context
import com.fileapex.data.settings.androidAppContextOrNull

actual object PendingUpdateStore {
    private const val PREFS = "fileapex_pending_update"
    private const val KEY_VERSION = "remote_version"
    private const val KEY_TITLE = "release_title"
    private const val KEY_NOTES = "release_notes"
    private const val KEY_ASSET_NAME = "asset_name"
    private const val KEY_ASSET_URL = "asset_url"
    private const val KEY_ASSET_SIZE = "asset_size"

    actual fun save(offer: PendingUpdateOffer?) {
        val context = androidAppContextOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (offer == null) {
            prefs.edit().clear().apply()
            return
        }
        prefs.edit()
            .putString(KEY_VERSION, offer.remoteVersion)
            .putString(KEY_TITLE, offer.releaseTitle.orEmpty())
            .putString(KEY_NOTES, offer.releaseNotes.orEmpty())
            .putString(KEY_ASSET_NAME, offer.assetName)
            .putString(KEY_ASSET_URL, offer.assetDownloadUrl)
            .putLong(KEY_ASSET_SIZE, offer.assetSizeBytes)
            .apply()
    }

    actual fun load(): PendingUpdateOffer? {
        val context = androidAppContextOrNull() ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_VERSION, null)?.trim().orEmpty()
        val assetName = prefs.getString(KEY_ASSET_NAME, null)?.trim().orEmpty()
        val assetUrl = prefs.getString(KEY_ASSET_URL, null)?.trim().orEmpty()
        if (version.isEmpty() || assetName.isEmpty() || assetUrl.isEmpty()) return null
        return PendingUpdateOffer(
            remoteVersion = version,
            releaseTitle = prefs.getString(KEY_TITLE, null)?.trim()?.takeIf { it.isNotEmpty() },
            releaseNotes = prefs.getString(KEY_NOTES, null)?.trim()?.takeIf { it.isNotEmpty() },
            assetName = assetName,
            assetDownloadUrl = assetUrl,
            assetSizeBytes = prefs.getLong(KEY_ASSET_SIZE, 0L)
        )
    }
}
