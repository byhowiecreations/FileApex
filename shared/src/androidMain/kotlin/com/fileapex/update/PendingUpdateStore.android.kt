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
    private const val KEY_LOCAL_FILE_PATH = "local_file_path"

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
            .putString(KEY_LOCAL_FILE_PATH, offer.localFilePath.orEmpty())
            .commit()
    }

    actual fun load(): PendingUpdateOffer? {
        val context = androidAppContextOrNull() ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_VERSION, null)?.trim().orEmpty()
        val assetName = prefs.getString(KEY_ASSET_NAME, null)?.trim().orEmpty()
        val assetUrl = prefs.getString(KEY_ASSET_URL, null)?.trim().orEmpty()
        val localPath = prefs.getString(KEY_LOCAL_FILE_PATH, null)?.trim()?.takeIf { it.isNotEmpty() }
        if (version.isEmpty() || assetName.isEmpty()) return null
        return PendingUpdateOffer(
            remoteVersion = version,
            releaseTitle = prefs.getString(KEY_TITLE, null)?.trim()?.takeIf { it.isNotEmpty() },
            releaseNotes = prefs.getString(KEY_NOTES, null)?.trim()?.takeIf { it.isNotEmpty() },
            assetName = assetName,
            assetDownloadUrl = assetUrl,
            assetSizeBytes = prefs.getLong(KEY_ASSET_SIZE, 0L),
            localFilePath = localPath
        )
    }

    private const val KEY_PROCESSED_NOTE_IDS = "processed_update_note_ids"
    private const val KEY_PROCESSED_FILE_SIGS = "processed_update_file_signatures"
    private const val KEY_LAST_INSTALLED_TIMESTAMP = "last_installed_apk_timestamp"

    private val inMemoryProcessedNoteIds = mutableSetOf<String>()
    private val inMemoryProcessedFileSigs = mutableSetOf<String>()
    private var inMemoryLastInstalledTimestamp: Long = 0L

    actual fun markProcessedNote(noteId: String, timestampEpochMs: Long, signature: String) {
        if (noteId.isNotBlank()) inMemoryProcessedNoteIds.add(noteId)
        if (signature.isNotBlank()) inMemoryProcessedFileSigs.add(signature)
        if (timestampEpochMs > 0L) {
            inMemoryLastInstalledTimestamp = maxOf(inMemoryLastInstalledTimestamp, timestampEpochMs)
        }
        val context = androidAppContextOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentNotes = prefs.getStringSet(KEY_PROCESSED_NOTE_IDS, emptySet()) ?: emptySet()
        val currentSigs = prefs.getStringSet(KEY_PROCESSED_FILE_SIGS, emptySet()) ?: emptySet()
        val storedTimestamp = prefs.getLong(KEY_LAST_INSTALLED_TIMESTAMP, 0L)
        val newTimestamp = maxOf(storedTimestamp, timestampEpochMs)

        val edit = prefs.edit()
        if (noteId.isNotBlank()) {
            edit.putStringSet(KEY_PROCESSED_NOTE_IDS, currentNotes + noteId)
        }
        if (signature.isNotBlank()) {
            edit.putStringSet(KEY_PROCESSED_FILE_SIGS, currentSigs + signature)
        }
        if (newTimestamp > storedTimestamp) {
            edit.putLong(KEY_LAST_INSTALLED_TIMESTAMP, newTimestamp)
        }
        edit.commit()
    }

    actual fun isNoteProcessed(noteId: String, timestampEpochMs: Long, signature: String): Boolean {
        if (noteId.isNotBlank() && noteId in inMemoryProcessedNoteIds) return true
        if (signature.isNotBlank() && signature in inMemoryProcessedFileSigs) return true
        if (timestampEpochMs > 0L && timestampEpochMs <= inMemoryLastInstalledTimestamp && inMemoryLastInstalledTimestamp > 0L) {
            return true
        }

        val context = androidAppContextOrNull() ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (noteId.isNotBlank()) {
            val notes = prefs.getStringSet(KEY_PROCESSED_NOTE_IDS, emptySet()) ?: emptySet()
            if (noteId in notes) return true
        }
        if (signature.isNotBlank()) {
            val sigs = prefs.getStringSet(KEY_PROCESSED_FILE_SIGS, emptySet()) ?: emptySet()
            if (signature in sigs) return true
        }
        if (timestampEpochMs > 0L) {
            val storedTimestamp = prefs.getLong(KEY_LAST_INSTALLED_TIMESTAMP, 0L)
            if (storedTimestamp > 0L && timestampEpochMs <= storedTimestamp) {
                return true
            }
        }
        return false
    }

    actual fun removeProcessedNote(noteId: String) {
        if (noteId.isBlank()) return
        inMemoryProcessedNoteIds.remove(noteId)
        val context = androidAppContextOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentNotes = prefs.getStringSet(KEY_PROCESSED_NOTE_IDS, emptySet()) ?: emptySet()
        if (noteId in currentNotes) {
            prefs.edit().putStringSet(KEY_PROCESSED_NOTE_IDS, currentNotes - noteId).commit()
        }
    }

    actual fun markProcessedFile(signature: String) {
        if (signature.isBlank()) return
        inMemoryProcessedFileSigs.add(signature)
        val context = androidAppContextOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentSigs = prefs.getStringSet(KEY_PROCESSED_FILE_SIGS, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(KEY_PROCESSED_FILE_SIGS, currentSigs + signature).commit()
    }

    actual fun isFileProcessed(signature: String): Boolean {
        if (signature.isBlank()) return false
        if (signature in inMemoryProcessedFileSigs) return true
        val context = androidAppContextOrNull() ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentSigs = prefs.getStringSet(KEY_PROCESSED_FILE_SIGS, emptySet()) ?: emptySet()
        return signature in currentSigs
    }

    actual fun removeProcessedFile(signature: String) {
        if (signature.isBlank()) return
        inMemoryProcessedFileSigs.remove(signature)
        val context = androidAppContextOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentSigs = prefs.getStringSet(KEY_PROCESSED_FILE_SIGS, emptySet()) ?: emptySet()
        if (signature in currentSigs) {
            prefs.edit().putStringSet(KEY_PROCESSED_FILE_SIGS, currentSigs - signature).commit()
        }
    }
}
