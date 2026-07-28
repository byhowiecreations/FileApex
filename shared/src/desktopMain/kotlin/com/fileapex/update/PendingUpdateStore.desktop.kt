package com.fileapex.update

import java.util.prefs.Preferences

actual object PendingUpdateStore {
    private val prefs: Preferences =
        Preferences.userRoot().node("com.fileapex.pending_update")

    actual fun save(offer: PendingUpdateOffer?) {
        if (offer == null) {
            prefs.clear()
            prefs.flush()
            return
        }
        prefs.put("remote_version", offer.remoteVersion)
        prefs.put("release_title", offer.releaseTitle.orEmpty())
        prefs.put("release_notes", offer.releaseNotes.orEmpty())
        prefs.put("asset_name", offer.assetName)
        prefs.put("asset_url", offer.assetDownloadUrl)
        prefs.putLong("asset_size", offer.assetSizeBytes)
        prefs.flush()
    }

    actual fun load(): PendingUpdateOffer? {
        val version = prefs.get("remote_version", "").trim()
        val assetName = prefs.get("asset_name", "").trim()
        val assetUrl = prefs.get("asset_url", "").trim()
        if (version.isEmpty() || assetName.isEmpty() || assetUrl.isEmpty()) return null
        return PendingUpdateOffer(
            remoteVersion = version,
            releaseTitle = prefs.get("release_title", "").trim().takeIf { it.isNotEmpty() },
            releaseNotes = prefs.get("release_notes", "").trim().takeIf { it.isNotEmpty() },
            assetName = assetName,
            assetDownloadUrl = assetUrl,
            assetSizeBytes = prefs.getLong("asset_size", 0L)
        )
    }
}
