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
    private const val KEY_ORIGIN_NOTE_ID = "origin_note_id"
    private const val KEY_TRANSACTION_ID = "transaction_id"
    private const val KEY_TRANSACTION_TIMESTAMP = "transaction_timestamp"

    actual fun save(offer: PendingUpdateOffer?) {
        val context = androidAppContextOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (offer == null) {
            prefs.edit()
                .remove(KEY_VERSION)
                .remove(KEY_TITLE)
                .remove(KEY_NOTES)
                .remove(KEY_ASSET_NAME)
                .remove(KEY_ASSET_URL)
                .remove(KEY_ASSET_SIZE)
                .remove(KEY_LOCAL_FILE_PATH)
                .remove(KEY_ORIGIN_NOTE_ID)
                .remove(KEY_TRANSACTION_ID)
                .remove(KEY_TRANSACTION_TIMESTAMP)
                .commit()
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
            .putString(KEY_ORIGIN_NOTE_ID, offer.originNoteId.orEmpty())
            .putString(KEY_TRANSACTION_ID, offer.transactionId.orEmpty())
            .putLong(KEY_TRANSACTION_TIMESTAMP, offer.transactionTimestampEpochMs ?: 0L)
            .commit()
    }

    actual fun load(): PendingUpdateOffer? {
        val context = androidAppContextOrNull() ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_VERSION, null)?.trim().orEmpty()
        val assetName = prefs.getString(KEY_ASSET_NAME, null)?.trim().orEmpty()
        val assetUrl = prefs.getString(KEY_ASSET_URL, null)?.trim().orEmpty()
        val localPath = prefs.getString(KEY_LOCAL_FILE_PATH, null)?.trim()?.takeIf { it.isNotEmpty() }
        val originNoteId = prefs.getString(KEY_ORIGIN_NOTE_ID, null)?.trim()?.takeIf { it.isNotEmpty() }
        val txId = prefs.getString(KEY_TRANSACTION_ID, null)?.trim()?.takeIf { it.isNotEmpty() }
        val txTimestamp = prefs.getLong(KEY_TRANSACTION_TIMESTAMP, 0L).takeIf { it > 0L }
        if (version.isEmpty() || assetName.isEmpty()) return null
        return PendingUpdateOffer(
            remoteVersion = version,
            releaseTitle = prefs.getString(KEY_TITLE, null)?.trim()?.takeIf { it.isNotEmpty() },
            releaseNotes = prefs.getString(KEY_NOTES, null)?.trim()?.takeIf { it.isNotEmpty() },
            assetName = assetName,
            assetDownloadUrl = assetUrl,
            assetSizeBytes = prefs.getLong(KEY_ASSET_SIZE, 0L),
            localFilePath = localPath,
            originNoteId = originNoteId,
            transactionId = txId,
            transactionTimestampEpochMs = txTimestamp
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

    private const val KEY_LAST_ATTEMPTED_NOTE_ID = "last_attempted_update_note_id"
    private const val KEY_NOTE_INSTALL_STATUS_PREFIX = "note_install_status_"

    private val inMemoryNoteInstallStatus = java.util.concurrent.ConcurrentHashMap<String, String>()
    @kotlin.concurrent.Volatile
    private var inMemoryLastAttemptedNoteId: String = ""

    actual fun setNoteInstallStatus(noteId: String, status: String) {
        if (noteId.isBlank()) return
        inMemoryNoteInstallStatus[noteId] = status
        val context = androidAppContextOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_NOTE_INSTALL_STATUS_PREFIX + noteId, status).commit()
    }

    actual fun getNoteInstallStatus(noteId: String): String? {
        if (noteId.isBlank()) return null
        inMemoryNoteInstallStatus[noteId]?.let { return it }
        val context = androidAppContextOrNull() ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val status = prefs.getString(KEY_NOTE_INSTALL_STATUS_PREFIX + noteId, null)
        if (status != null) {
            inMemoryNoteInstallStatus[noteId] = status
        }
        return status
    }

    actual fun isNoteInstalled(noteId: String): Boolean =
        getNoteInstallStatus(noteId) == "INSTALLED"

    actual fun setLastAttemptedNoteId(noteId: String) {
        inMemoryLastAttemptedNoteId = noteId
        val context = androidAppContextOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_ATTEMPTED_NOTE_ID, noteId).commit()
    }

    actual fun getLastAttemptedNoteId(): String {
        if (inMemoryLastAttemptedNoteId.isNotBlank()) return inMemoryLastAttemptedNoteId
        val context = androidAppContextOrNull() ?: return ""
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_LAST_ATTEMPTED_NOTE_ID, "").orEmpty()
        inMemoryLastAttemptedNoteId = stored
        return stored
    }

    private const val KEY_LAST_ATTEMPTED_TX_ID = "last_attempted_update_tx_id"
    private const val KEY_LAST_ATTEMPTED_INSTALL_TIMESTAMP = "last_attempted_install_timestamp"
    private const val KEY_TX_INSTALL_STATUS_PREFIX = "tx_install_status_"
    private const val KEY_TX_RECORD_PREFIX = "tx_record_"
    private const val KEY_ACTIVE_TX_IDS = "active_transfer_tx_ids"

    private val inMemoryTxInstallStatus = java.util.concurrent.ConcurrentHashMap<String, String>()
    @kotlin.concurrent.Volatile
    private var inMemoryLastAttemptedTxId: String = ""
    @kotlin.concurrent.Volatile
    private var inMemoryLastAttemptedInstallTimestamp: Long = 0L

    actual fun setLastAttemptedTransactionId(transactionId: String) {
        inMemoryLastAttemptedTxId = transactionId
        val context = androidAppContextOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_ATTEMPTED_TX_ID, transactionId).commit()
    }

    actual fun getLastAttemptedTransactionId(): String {
        if (inMemoryLastAttemptedTxId.isNotBlank()) return inMemoryLastAttemptedTxId
        val context = androidAppContextOrNull() ?: return ""
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_LAST_ATTEMPTED_TX_ID, "").orEmpty()
        inMemoryLastAttemptedTxId = stored
        return stored
    }

    actual fun setLastAttemptedInstallTimestamp(timestampEpochMs: Long) {
        inMemoryLastAttemptedInstallTimestamp = timestampEpochMs
        val context = androidAppContextOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_ATTEMPTED_INSTALL_TIMESTAMP, timestampEpochMs).commit()
    }

    actual fun getLastAttemptedInstallTimestamp(): Long {
        if (inMemoryLastAttemptedInstallTimestamp > 0L) return inMemoryLastAttemptedInstallTimestamp
        val context = androidAppContextOrNull() ?: return 0L
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getLong(KEY_LAST_ATTEMPTED_INSTALL_TIMESTAMP, 0L)
        inMemoryLastAttemptedInstallTimestamp = stored
        return stored
    }

    actual fun setTransactionInstallStatus(transactionId: String, status: String) {
        if (transactionId.isBlank()) return
        inMemoryTxInstallStatus[transactionId] = status
        val context = androidAppContextOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TX_INSTALL_STATUS_PREFIX + transactionId, status).commit()
        val record = getTransactionRecord(transactionId)
        if (record != null) {
            val updated = record.copy(
                installAttempted = status == "INSTALLING" || status == "INSTALLED",
                installed = status == "INSTALLED"
            )
            saveTransactionRecord(updated)
        }
    }

    actual fun getTransactionInstallStatus(transactionId: String): String? {
        if (transactionId.isBlank()) return null
        inMemoryTxInstallStatus[transactionId]?.let { return it }
        val context = androidAppContextOrNull() ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val status = prefs.getString(KEY_TX_INSTALL_STATUS_PREFIX + transactionId, null)
        if (status != null) {
            inMemoryTxInstallStatus[transactionId] = status
        }
        return status
    }

    actual fun isTransactionInstalled(transactionId: String): Boolean =
        getTransactionInstallStatus(transactionId) == "INSTALLED"

    actual fun saveTransactionRecord(record: com.fileapex.network.TransferTransactionRecord) {
        val context = androidAppContextOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = kotlinx.serialization.json.Json.encodeToString(
            com.fileapex.network.TransferTransactionRecord.serializer(),
            record
        )
        val activeIds = prefs.getStringSet(KEY_ACTIVE_TX_IDS, emptySet()) ?: emptySet()
        val newSet = if (activeIds.size >= 50) {
            (activeIds.drop(1) + record.transactionId).toSet()
        } else {
            activeIds + record.transactionId
        }
        prefs.edit()
            .putString(KEY_TX_RECORD_PREFIX + record.transactionId, json)
            .putStringSet(KEY_ACTIVE_TX_IDS, newSet)
            .commit()
    }

    actual fun getTransactionRecord(transactionId: String): com.fileapex.network.TransferTransactionRecord? {
        if (transactionId.isBlank()) return null
        val context = androidAppContextOrNull() ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TX_RECORD_PREFIX + transactionId, null) ?: return null
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString(
                com.fileapex.network.TransferTransactionRecord.serializer(),
                raw
            )
        }.getOrNull()
    }

    actual fun findTransactionByFilePath(filePath: String): com.fileapex.network.TransferTransactionRecord? {
        if (filePath.isBlank()) return null
        val context = androidAppContextOrNull() ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activeIds = prefs.getStringSet(KEY_ACTIVE_TX_IDS, emptySet()) ?: emptySet()
        for (id in activeIds) {
            val record = getTransactionRecord(id) ?: continue
            if (record.finalPath == filePath || record.targetPath == filePath) {
                return record
            }
        }
        return null
    }

    actual fun markTransactionInstallAttempted(transactionId: String) {
        setTransactionInstallStatus(transactionId, "INSTALLING")
    }

    actual fun markTransactionInstalled(transactionId: String) {
        setTransactionInstallStatus(transactionId, "INSTALLED")
    }

    actual fun purgeTransaction(transactionId: String) {
        if (transactionId.isBlank()) return
        inMemoryTxInstallStatus.remove(transactionId)
        val context = androidAppContextOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activeIds = prefs.getStringSet(KEY_ACTIVE_TX_IDS, emptySet()) ?: emptySet()
        prefs.edit()
            .remove(KEY_TX_RECORD_PREFIX + transactionId)
            .remove(KEY_TX_INSTALL_STATUS_PREFIX + transactionId)
            .putStringSet(KEY_ACTIVE_TX_IDS, activeIds - transactionId)
            .commit()
    }

    actual fun deleteUpdateApkAndCompleteTransaction(transactionId: String): Boolean {
        if (transactionId.isBlank()) return false
        val txRecord = getTransactionRecord(transactionId)
            ?: com.fileapex.network.TransferTransactionJournal.findCompleted(transactionId, "", 0L)
        val offer = load()
        val candidatePath = txRecord?.finalPath?.takeIf { it.isNotBlank() }
            ?: txRecord?.targetPath?.takeIf { it.isNotBlank() }
            ?: (if (offer?.transactionId == transactionId) offer?.localFilePath else null)

        if (!candidatePath.isNullOrBlank() && candidatePath.endsWith(".apk", ignoreCase = true)) {
            val file = java.io.File(candidatePath)
            if (file.isFile) {
                val deleted = runCatching { file.delete() }.getOrDefault(false)
                println("PendingUpdateStore: deleted update APK for txId=$transactionId at $candidatePath (deleted=$deleted)")
            } else {
                println("PendingUpdateStore: candidate update file missing or not a file: $candidatePath")
            }
        } else {
            println("PendingUpdateStore: no candidate APK file found for txId=$transactionId")
        }

        setTransactionInstallStatus(transactionId, "INSTALLED")
        com.fileapex.network.TransferTransactionJournal.markInstalled(transactionId)
        offer?.originNoteId?.takeIf { it.isNotBlank() }?.let { noteId ->
            setNoteInstallStatus(noteId, "INSTALLED")
            setLastAttemptedNoteId("")
        }
        setLastAttemptedTransactionId("")
        setLastAttemptedInstallTimestamp(0L)
        save(null)
        return true
    }
}
