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
            prefs.remove("transaction_id")
            prefs.remove("transaction_timestamp")
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
        prefs.put("transaction_id", offer.transactionId.orEmpty())
        prefs.putLong("transaction_timestamp", offer.transactionTimestampEpochMs ?: 0L)
        prefs.flush()
    }

    actual fun load(): PendingUpdateOffer? {
        val version = prefs.get("remote_version", "").trim()
        val assetName = prefs.get("asset_name", "").trim()
        val assetUrl = prefs.get("asset_url", "").trim()
        val localPath = prefs.get("local_file_path", "").trim().takeIf { it.isNotEmpty() }
        val originNoteId = prefs.get("origin_note_id", "").trim().takeIf { it.isNotEmpty() }
        val txId = prefs.get("transaction_id", "").trim().takeIf { it.isNotEmpty() }
        val txTimestamp = prefs.getLong("transaction_timestamp", 0L).takeIf { it > 0L }
        if (version.isEmpty() || assetName.isEmpty()) return null
        return PendingUpdateOffer(
            remoteVersion = version,
            releaseTitle = prefs.get("release_title", "").trim().takeIf { it.isNotEmpty() },
            releaseNotes = prefs.get("release_notes", "").trim().takeIf { it.isNotEmpty() },
            assetName = assetName,
            assetDownloadUrl = assetUrl,
            assetSizeBytes = prefs.getLong("asset_size", 0L),
            localFilePath = localPath,
            originNoteId = originNoteId,
            transactionId = txId,
            transactionTimestampEpochMs = txTimestamp
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

    private val txInstallStatus = mutableMapOf<String, String>()
    private var lastAttemptedTxId: String = ""

    actual fun setLastAttemptedTransactionId(transactionId: String) {
        lastAttemptedTxId = transactionId
        prefs.put("last_attempted_update_tx_id", transactionId)
        prefs.flush()
    }

    actual fun getLastAttemptedTransactionId(): String {
        if (lastAttemptedTxId.isNotBlank()) return lastAttemptedTxId
        val stored = prefs.get("last_attempted_update_tx_id", "")?.trim().orEmpty()
        lastAttemptedTxId = stored
        return stored
    }

    actual fun setTransactionInstallStatus(transactionId: String, status: String) {
        if (transactionId.isBlank()) return
        txInstallStatus[transactionId] = status
        prefs.put("tx_install_status_$transactionId", status)
        prefs.flush()
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
        txInstallStatus[transactionId]?.let { return it }
        val stored = prefs.get("tx_install_status_$transactionId", null)?.trim()?.takeIf { it.isNotEmpty() }
        if (stored != null) {
            txInstallStatus[transactionId] = stored
        }
        return stored
    }

    actual fun isTransactionInstalled(transactionId: String): Boolean =
        getTransactionInstallStatus(transactionId) == "INSTALLED"

    actual fun saveTransactionRecord(record: com.fileapex.network.TransferTransactionRecord) {
        val json = kotlinx.serialization.json.Json.encodeToString(
            com.fileapex.network.TransferTransactionRecord.serializer(),
            record
        )
        val activeIds = prefs.get("active_transfer_tx_ids", "")
            .split(",")
            .filter { it.isNotBlank() }
            .toSet()
        val newSet = if (activeIds.size >= 50) {
            (activeIds.drop(1) + record.transactionId).toSet()
        } else {
            activeIds + record.transactionId
        }
        prefs.put("tx_record_" + record.transactionId, json)
        prefs.put("active_transfer_tx_ids", newSet.joinToString(","))
        prefs.flush()
    }

    actual fun getTransactionRecord(transactionId: String): com.fileapex.network.TransferTransactionRecord? {
        if (transactionId.isBlank()) return null
        val raw = prefs.get("tx_record_$transactionId", null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString(
                com.fileapex.network.TransferTransactionRecord.serializer(),
                raw
            )
        }.getOrNull()
    }

    actual fun findTransactionByFilePath(filePath: String): com.fileapex.network.TransferTransactionRecord? {
        if (filePath.isBlank()) return null
        val activeIds = prefs.get("active_transfer_tx_ids", "")
            .split(",")
            .filter { it.isNotBlank() }
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
        txInstallStatus.remove(transactionId)
        val activeIds = prefs.get("active_transfer_tx_ids", "")
            .split(",")
            .filter { it.isNotBlank() && it != transactionId }
        prefs.remove("tx_record_$transactionId")
        prefs.remove("tx_install_status_$transactionId")
        prefs.put("active_transfer_tx_ids", activeIds.joinToString(","))
        prefs.flush()
    }

    actual fun deleteUpdateApkAndCompleteTransaction(transactionId: String): Boolean {
        if (transactionId.isBlank()) return false
        val txRecord = getTransactionRecord(transactionId)
            ?: com.fileapex.network.TransferTransactionJournal.findCompleted(transactionId, "", 0L)
        val offer = load()
        val candidatePath = txRecord?.finalPath?.takeIf { it.isNotBlank() }
            ?: txRecord?.targetPath?.takeIf { it.isNotBlank() }
            ?: (if (offer?.transactionId == transactionId) offer?.localFilePath else null)

        if (!candidatePath.isNullOrBlank() && (candidatePath.endsWith(".apk", ignoreCase = true) || candidatePath.endsWith(".dmg", ignoreCase = true))) {
            val file = java.io.File(candidatePath)
            if (file.isFile) {
                val deleted = runCatching { file.delete() }.getOrDefault(false)
                println("PendingUpdateStore: deleted update package for txId=$transactionId at $candidatePath (deleted=$deleted)")
            } else {
                println("PendingUpdateStore: candidate update file missing or not a file: $candidatePath")
            }
        }

        setTransactionInstallStatus(transactionId, "INSTALLED")
        com.fileapex.network.TransferTransactionJournal.markInstalled(transactionId)
        offer?.originNoteId?.takeIf { it.isNotBlank() }?.let { noteId ->
            setNoteInstallStatus(noteId, "INSTALLED")
            setLastAttemptedNoteId("")
        }
        setLastAttemptedTransactionId("")
        save(null)
        return true
    }
}
