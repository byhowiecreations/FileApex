package com.fileapex.update

import java.util.prefs.Preferences

actual object PendingUpdateStore {
    private val prefs: Preferences =
        Preferences.userRoot().node("com.fileapex.pending_update")

    actual fun save(offer: PendingUpdateOffer?) {
        if (offer == null) {
            prefs.remove("remote_version")
            prefs.remove("release_title")
            prefs.remove("release_notes")
            prefs.remove("asset_name")
            prefs.remove("asset_url")
            prefs.remove("asset_size")
            prefs.remove("local_file_path")
            prefs.remove("origin_note_id")
            prefs.flush()
            return
        }
        prefs.put("remote_version", offer.remoteVersion)
        prefs.put("release_title", offer.releaseTitle.orEmpty())
        prefs.put("release_notes", offer.releaseNotes.orEmpty())
        prefs.put("asset_name", offer.assetName)
        prefs.put("asset_url", offer.assetDownloadUrl)
        prefs.putLong("asset_size", offer.assetSizeBytes)
        prefs.put("local_file_path", offer.localFilePath.orEmpty())
        prefs.put("origin_note_id", offer.originNoteId.orEmpty())
        prefs.flush()
    }

    actual fun load(): PendingUpdateOffer? {
        val version = prefs.get("remote_version", "").trim()
        val assetName = prefs.get("asset_name", "").trim()
        val assetUrl = prefs.get("asset_url", "").trim()
        val localPath = prefs.get("local_file_path", "").trim().takeIf { it.isNotEmpty() }
        val originNoteId = prefs.get("origin_note_id", "").trim().takeIf { it.isNotEmpty() }
        if (version.isEmpty() || assetName.isEmpty()) return null
        return PendingUpdateOffer(
            remoteVersion = version,
            releaseTitle = prefs.get("release_title", "").trim().takeIf { it.isNotEmpty() },
            releaseNotes = prefs.get("release_notes", "").trim().takeIf { it.isNotEmpty() },
            assetName = assetName,
            assetDownloadUrl = assetUrl,
            assetSizeBytes = prefs.getLong("asset_size", 0L),
            localFilePath = localPath,
            originNoteId = originNoteId
        )
    }

    private val processedNoteIds = mutableSetOf<String>()
    private val processedFileSigs = mutableSetOf<String>()
    private var lastInstalledTimestamp: Long = 0L

    actual fun markProcessedNote(noteId: String, timestampEpochMs: Long, signature: String) {
        if (noteId.isNotBlank()) processedNoteIds.add(noteId)
        if (signature.isNotBlank()) processedFileSigs.add(signature)
        if (timestampEpochMs > 0L) {
            lastInstalledTimestamp = maxOf(lastInstalledTimestamp, timestampEpochMs)
        }
    }

    actual fun isNoteProcessed(noteId: String, timestampEpochMs: Long, signature: String): Boolean {
        if (noteId.isNotBlank() && noteId in processedNoteIds) return true
        if (signature.isNotBlank() && signature in processedFileSigs) return true
        if (timestampEpochMs > 0L && timestampEpochMs <= lastInstalledTimestamp && lastInstalledTimestamp > 0L) {
            return true
        }
        return false
    }

    actual fun removeProcessedNote(noteId: String) {
        if (noteId.isNotBlank()) processedNoteIds.remove(noteId)
    }

    actual fun markProcessedFile(signature: String) {
        if (signature.isNotBlank()) processedFileSigs.add(signature)
    }

    actual fun isFileProcessed(signature: String): Boolean {
        return signature.isNotBlank() && signature in processedFileSigs
    }

    actual fun removeProcessedFile(signature: String) {
        if (signature.isNotBlank()) processedFileSigs.remove(signature)
    }

    private val noteInstallStatus = mutableMapOf<String, String>()
    private var lastAttemptedNoteId: String = ""

    actual fun setNoteInstallStatus(noteId: String, status: String) {
        if (noteId.isBlank()) return
        noteInstallStatus[noteId] = status
        prefs.put("note_install_status_$noteId", status)
        prefs.flush()
    }

    actual fun getNoteInstallStatus(noteId: String): String? {
        if (noteId.isBlank()) return null
        noteInstallStatus[noteId]?.let { return it }
        val stored = prefs.get("note_install_status_$noteId", null)?.trim()?.takeIf { it.isNotEmpty() }
        if (stored != null) {
            noteInstallStatus[noteId] = stored
        }
        return stored
    }

    actual fun isNoteInstalled(noteId: String): Boolean =
        getNoteInstallStatus(noteId) == "INSTALLED"

    actual fun setLastAttemptedNoteId(noteId: String) {
        lastAttemptedNoteId = noteId
        prefs.put("last_attempted_update_note_id", noteId)
        prefs.flush()
    }

    actual fun getLastAttemptedNoteId(): String {
        if (lastAttemptedNoteId.isNotBlank()) return lastAttemptedNoteId
        val stored = prefs.get("last_attempted_update_note_id", "")?.trim().orEmpty()
        lastAttemptedNoteId = stored
        return stored
    }
}
