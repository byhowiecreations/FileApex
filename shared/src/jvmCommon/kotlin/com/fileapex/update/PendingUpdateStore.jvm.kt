package com.fileapex.update

import com.fileapex.network.TransferTransactionJournal
import com.fileapex.network.TransferTransactionRecord
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json

actual object PendingUpdateStore {
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
    private const val KEY_PROCESSED_NOTE_IDS = "processed_update_note_ids"
    private const val KEY_PROCESSED_FILE_SIGS = "processed_update_file_signatures"
    private const val KEY_LAST_INSTALLED_TIMESTAMP = "last_installed_apk_timestamp"
    private const val KEY_LAST_ATTEMPTED_NOTE_ID = "last_attempted_update_note_id"
    private const val KEY_NOTE_INSTALL_STATUS_PREFIX = "note_install_status_"
    private const val KEY_LAST_ATTEMPTED_TX_ID = "last_attempted_update_tx_id"
    private const val KEY_LAST_ATTEMPTED_INSTALL_TIMESTAMP = "last_attempted_install_timestamp"
    private const val KEY_TX_INSTALL_STATUS_PREFIX = "tx_install_status_"
    private const val KEY_TX_RECORD_PREFIX = "tx_record_"
    private const val KEY_ACTIVE_TX_IDS = "active_transfer_tx_ids"
    private const val MAX_ACTIVE_TRANSACTIONS = 50

    private val inMemoryProcessedNoteIds = ConcurrentHashMap.newKeySet<String>()
    private val inMemoryProcessedFileSigs = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var inMemoryLastInstalledTimestamp: Long = 0L
    private val inMemoryNoteInstallStatus = ConcurrentHashMap<String, String>()
    private val inMemoryTxInstallStatus = ConcurrentHashMap<String, String>()
    @Volatile
    private var inMemoryLastAttemptedNoteId: String = ""
    @Volatile
    private var inMemoryLastAttemptedTxId: String = ""
    @Volatile
    private var inMemoryLastAttemptedInstallTimestamp: Long = 0L

    private fun UpdateKeyValueStore.trimmed(key: String): String? =
        getString(key)?.trim()?.takeIf { it.isNotEmpty() }

    actual fun save(offer: PendingUpdateOffer?) {
        val store = openUpdateKeyValueStore() ?: return
        store.edit {
            if (offer == null) {
                listOf(
                    KEY_VERSION, KEY_TITLE, KEY_NOTES, KEY_ASSET_NAME, KEY_ASSET_URL, KEY_ASSET_SIZE,
                    KEY_LOCAL_FILE_PATH, KEY_ORIGIN_NOTE_ID, KEY_TRANSACTION_ID, KEY_TRANSACTION_TIMESTAMP
                ).forEach(::remove)
            } else {
                putString(KEY_VERSION, offer.remoteVersion)
                putString(KEY_TITLE, offer.releaseTitle.orEmpty())
                putString(KEY_NOTES, offer.releaseNotes.orEmpty())
                putString(KEY_ASSET_NAME, offer.assetName)
                putString(KEY_ASSET_URL, offer.assetDownloadUrl)
                putLong(KEY_ASSET_SIZE, offer.assetSizeBytes)
                putString(KEY_LOCAL_FILE_PATH, offer.localFilePath.orEmpty())
                putString(KEY_ORIGIN_NOTE_ID, offer.originNoteId.orEmpty())
                putString(KEY_TRANSACTION_ID, offer.transactionId.orEmpty())
                putLong(KEY_TRANSACTION_TIMESTAMP, offer.transactionTimestampEpochMs ?: 0L)
            }
        }
    }

    actual fun load(): PendingUpdateOffer? {
        val store = openUpdateKeyValueStore() ?: return null
        val version = store.trimmed(KEY_VERSION).orEmpty()
        val assetName = store.trimmed(KEY_ASSET_NAME).orEmpty()
        val assetUrl = store.trimmed(KEY_ASSET_URL).orEmpty()
        if (version.isEmpty() || assetName.isEmpty()) return null
        return PendingUpdateOffer(
            remoteVersion = version,
            releaseTitle = store.trimmed(KEY_TITLE),
            releaseNotes = store.trimmed(KEY_NOTES),
            assetName = assetName,
            assetDownloadUrl = assetUrl,
            assetSizeBytes = store.getLong(KEY_ASSET_SIZE),
            localFilePath = store.trimmed(KEY_LOCAL_FILE_PATH),
            originNoteId = store.trimmed(KEY_ORIGIN_NOTE_ID),
            transactionId = store.trimmed(KEY_TRANSACTION_ID),
            transactionTimestampEpochMs = store.getLong(KEY_TRANSACTION_TIMESTAMP).takeIf { it > 0L }
        )
    }

    actual fun markProcessedNote(noteId: String, timestampEpochMs: Long, signature: String) {
        if (noteId.isNotBlank()) inMemoryProcessedNoteIds.add(noteId)
        if (signature.isNotBlank()) inMemoryProcessedFileSigs.add(signature)
        if (timestampEpochMs > 0L) {
            inMemoryLastInstalledTimestamp = maxOf(inMemoryLastInstalledTimestamp, timestampEpochMs)
        }
        val store = openUpdateKeyValueStore() ?: return
        val storedTimestamp = store.getLong(KEY_LAST_INSTALLED_TIMESTAMP)
        val newTimestamp = maxOf(storedTimestamp, timestampEpochMs)
        val notes = store.getStringSet(KEY_PROCESSED_NOTE_IDS)
        val sigs = store.getStringSet(KEY_PROCESSED_FILE_SIGS)
        store.edit {
            if (noteId.isNotBlank()) putStringSet(KEY_PROCESSED_NOTE_IDS, notes + noteId)
            if (signature.isNotBlank()) putStringSet(KEY_PROCESSED_FILE_SIGS, sigs + signature)
            if (newTimestamp > storedTimestamp) putLong(KEY_LAST_INSTALLED_TIMESTAMP, newTimestamp)
        }
    }

    actual fun isNoteProcessed(noteId: String, timestampEpochMs: Long, signature: String): Boolean {
        if (noteId.isNotBlank() && noteId in inMemoryProcessedNoteIds) return true
        if (signature.isNotBlank() && signature in inMemoryProcessedFileSigs) return true
        if (timestampEpochMs > 0L && timestampEpochMs <= inMemoryLastInstalledTimestamp && inMemoryLastInstalledTimestamp > 0L) {
            return true
        }
        val store = openUpdateKeyValueStore() ?: return false
        if (noteId.isNotBlank() && noteId in store.getStringSet(KEY_PROCESSED_NOTE_IDS)) return true
        if (signature.isNotBlank() && signature in store.getStringSet(KEY_PROCESSED_FILE_SIGS)) return true
        if (timestampEpochMs > 0L) {
            val storedTimestamp = store.getLong(KEY_LAST_INSTALLED_TIMESTAMP)
            if (storedTimestamp > 0L && timestampEpochMs <= storedTimestamp) return true
        }
        return false
    }

    actual fun removeProcessedNote(noteId: String) {
        if (noteId.isBlank()) return
        inMemoryProcessedNoteIds.remove(noteId)
        val store = openUpdateKeyValueStore() ?: return
        val notes = store.getStringSet(KEY_PROCESSED_NOTE_IDS)
        if (noteId in notes) store.edit { putStringSet(KEY_PROCESSED_NOTE_IDS, notes - noteId) }
    }

    actual fun markProcessedFile(signature: String) {
        if (signature.isBlank()) return
        inMemoryProcessedFileSigs.add(signature)
        val store = openUpdateKeyValueStore() ?: return
        val sigs = store.getStringSet(KEY_PROCESSED_FILE_SIGS)
        store.edit { putStringSet(KEY_PROCESSED_FILE_SIGS, sigs + signature) }
    }

    actual fun isFileProcessed(signature: String): Boolean {
        if (signature.isBlank()) return false
        if (signature in inMemoryProcessedFileSigs) return true
        val store = openUpdateKeyValueStore() ?: return false
        return signature in store.getStringSet(KEY_PROCESSED_FILE_SIGS)
    }

    actual fun removeProcessedFile(signature: String) {
        if (signature.isBlank()) return
        inMemoryProcessedFileSigs.remove(signature)
        val store = openUpdateKeyValueStore() ?: return
        val sigs = store.getStringSet(KEY_PROCESSED_FILE_SIGS)
        if (signature in sigs) store.edit { putStringSet(KEY_PROCESSED_FILE_SIGS, sigs - signature) }
    }

    actual fun setNoteInstallStatus(noteId: String, status: String) {
        if (noteId.isBlank()) return
        inMemoryNoteInstallStatus[noteId] = status
        openUpdateKeyValueStore()?.edit { putString(KEY_NOTE_INSTALL_STATUS_PREFIX + noteId, status) }
    }

    actual fun getNoteInstallStatus(noteId: String): String? {
        if (noteId.isBlank()) return null
        inMemoryNoteInstallStatus[noteId]?.let { return it }
        val status = openUpdateKeyValueStore()?.trimmed(KEY_NOTE_INSTALL_STATUS_PREFIX + noteId)
        if (status != null) inMemoryNoteInstallStatus[noteId] = status
        return status
    }

    actual fun isNoteInstalled(noteId: String): Boolean =
        getNoteInstallStatus(noteId) == "INSTALLED"

    actual fun setLastAttemptedNoteId(noteId: String) {
        inMemoryLastAttemptedNoteId = noteId
        openUpdateKeyValueStore()?.edit { putString(KEY_LAST_ATTEMPTED_NOTE_ID, noteId) }
    }

    actual fun getLastAttemptedNoteId(): String {
        if (inMemoryLastAttemptedNoteId.isNotBlank()) return inMemoryLastAttemptedNoteId
        val stored = openUpdateKeyValueStore()?.trimmed(KEY_LAST_ATTEMPTED_NOTE_ID).orEmpty()
        inMemoryLastAttemptedNoteId = stored
        return stored
    }

    actual fun setLastAttemptedTransactionId(transactionId: String) {
        inMemoryLastAttemptedTxId = transactionId
        openUpdateKeyValueStore()?.edit { putString(KEY_LAST_ATTEMPTED_TX_ID, transactionId) }
    }

    actual fun getLastAttemptedTransactionId(): String {
        if (inMemoryLastAttemptedTxId.isNotBlank()) return inMemoryLastAttemptedTxId
        val stored = openUpdateKeyValueStore()?.trimmed(KEY_LAST_ATTEMPTED_TX_ID).orEmpty()
        inMemoryLastAttemptedTxId = stored
        return stored
    }

    actual fun setLastAttemptedInstallTimestamp(timestampEpochMs: Long) {
        inMemoryLastAttemptedInstallTimestamp = timestampEpochMs
        openUpdateKeyValueStore()?.edit { putLong(KEY_LAST_ATTEMPTED_INSTALL_TIMESTAMP, timestampEpochMs) }
    }

    actual fun getLastAttemptedInstallTimestamp(): Long {
        if (inMemoryLastAttemptedInstallTimestamp > 0L) return inMemoryLastAttemptedInstallTimestamp
        val stored = openUpdateKeyValueStore()?.getLong(KEY_LAST_ATTEMPTED_INSTALL_TIMESTAMP) ?: 0L
        inMemoryLastAttemptedInstallTimestamp = stored
        return stored
    }

    actual fun setTransactionInstallStatus(transactionId: String, status: String) {
        if (transactionId.isBlank()) return
        inMemoryTxInstallStatus[transactionId] = status
        val store = openUpdateKeyValueStore() ?: return
        store.edit { putString(KEY_TX_INSTALL_STATUS_PREFIX + transactionId, status) }
        getTransactionRecord(transactionId)?.let { record ->
            saveTransactionRecord(
                record.copy(
                    installAttempted = status == "INSTALLING" || status == "INSTALLED",
                    installed = status == "INSTALLED"
                )
            )
        }
    }

    actual fun getTransactionInstallStatus(transactionId: String): String? {
        if (transactionId.isBlank()) return null
        inMemoryTxInstallStatus[transactionId]?.let { return it }
        val status = openUpdateKeyValueStore()?.trimmed(KEY_TX_INSTALL_STATUS_PREFIX + transactionId)
        if (status != null) inMemoryTxInstallStatus[transactionId] = status
        return status
    }

    actual fun isTransactionInstalled(transactionId: String): Boolean =
        getTransactionInstallStatus(transactionId) == "INSTALLED"

    actual fun saveTransactionRecord(record: TransferTransactionRecord) {
        val store = openUpdateKeyValueStore() ?: return
        val json = Json.encodeToString(TransferTransactionRecord.serializer(), record)
        val activeIds = store.getStringSet(KEY_ACTIVE_TX_IDS)
        val newSet = if (activeIds.size >= MAX_ACTIVE_TRANSACTIONS) {
            (activeIds.drop(1) + record.transactionId).toSet()
        } else {
            activeIds + record.transactionId
        }
        store.edit {
            putString(KEY_TX_RECORD_PREFIX + record.transactionId, json)
            putStringSet(KEY_ACTIVE_TX_IDS, newSet)
        }
    }

    actual fun getTransactionRecord(transactionId: String): TransferTransactionRecord? {
        if (transactionId.isBlank()) return null
        val raw = openUpdateKeyValueStore()?.trimmed(KEY_TX_RECORD_PREFIX + transactionId) ?: return null
        return runCatching {
            Json.decodeFromString(TransferTransactionRecord.serializer(), raw)
        }.getOrNull()
    }

    actual fun findTransactionByFilePath(filePath: String): TransferTransactionRecord? {
        if (filePath.isBlank()) return null
        val store = openUpdateKeyValueStore() ?: return null
        for (id in store.getStringSet(KEY_ACTIVE_TX_IDS)) {
            val record = getTransactionRecord(id) ?: continue
            if (record.finalPath == filePath || record.targetPath == filePath) return record
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
        val store = openUpdateKeyValueStore() ?: return
        val activeIds = store.getStringSet(KEY_ACTIVE_TX_IDS)
        store.edit {
            remove(KEY_TX_RECORD_PREFIX + transactionId)
            remove(KEY_TX_INSTALL_STATUS_PREFIX + transactionId)
            putStringSet(KEY_ACTIVE_TX_IDS, activeIds - transactionId)
        }
    }

    actual fun deleteUpdateApkAndCompleteTransaction(transactionId: String): Boolean {
        if (transactionId.isBlank()) return false
        val txRecord = getTransactionRecord(transactionId)
            ?: TransferTransactionJournal.findCompleted(transactionId, "", 0L)
        val offer = load()
        val candidatePath = txRecord?.finalPath?.takeIf { it.isNotBlank() }
            ?: txRecord?.targetPath?.takeIf { it.isNotBlank() }
            ?: (if (offer?.transactionId == transactionId) offer?.localFilePath else null)

        if (!candidatePath.isNullOrBlank() && updatePackageExtensions.any { candidatePath.endsWith(it, ignoreCase = true) }) {
            val file = java.io.File(candidatePath)
            if (file.isFile) {
                val deleted = runCatching { file.delete() }.getOrDefault(false)
                println("PendingUpdateStore: deleted update package for txId=$transactionId (deleted=$deleted)")
            } else {
                println("PendingUpdateStore: candidate update file missing or not a file")
            }
        } else {
            println("PendingUpdateStore: no candidate update package found for txId=$transactionId")
        }

        setTransactionInstallStatus(transactionId, "INSTALLED")
        TransferTransactionJournal.markInstalled(transactionId)
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
