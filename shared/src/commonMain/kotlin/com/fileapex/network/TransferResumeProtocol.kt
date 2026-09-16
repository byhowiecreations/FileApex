package com.fileapex.network

import com.fileapex.platform.UniqueFileNames
import kotlinx.serialization.Serializable

/**
 * Byte-offset handshake for on-demand LAN socket transfers (v0.8.2a).
 * Receiver reports how many bytes it already has on disk; sender seeks and streams the rest.
 */
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

@Serializable
data class ResumeOffsetResponse(
    val offset: Long,
    val complete: Boolean = false
)

@Serializable
data class TransferTransactionRecord(
    val transactionId: String,
    val senderDeviceId: String,
    val targetPath: String,
    val finalPath: String,
    val byteSize: Long,
    val timestampEpochMs: Long,
    val completedAtEpochMs: Long,
    val installAttempted: Boolean = false,
    val installed: Boolean = false
)

object TransferTransactionJournal {
    private const val MAX_ENTRIES = 200
    private const val SESSION_TTL_MS = 15 * 60 * 1000L
    private val records = mutableListOf<TransferTransactionRecord>()
    private val lock = Any()

    fun recordCompleted(
        transactionId: String,
        senderDeviceId: String,
        targetPath: String,
        finalPath: String,
        byteSize: Long,
        timestampEpochMs: Long
    ) {
        if (transactionId.isBlank()) return
        val now = com.fileapex.util.TimeUtils.now()
        val record = TransferTransactionRecord(
            transactionId = transactionId,
            senderDeviceId = senderDeviceId,
            targetPath = targetPath,
            finalPath = finalPath,
            byteSize = byteSize,
            timestampEpochMs = timestampEpochMs,
            completedAtEpochMs = now
        )
        synchronized(lock) {
            records.removeAll { now - it.completedAtEpochMs > SESSION_TTL_MS }
            if (records.size >= MAX_ENTRIES) {
                records.removeAt(0)
            }
            records.add(record)
        }
        com.fileapex.update.PendingUpdateStore.saveTransactionRecord(record)
    }

    fun findCompleted(
        transactionId: String,
        senderDeviceId: String,
        expectedSize: Long
    ): TransferTransactionRecord? {
        if (transactionId.isBlank()) return null
        val now = com.fileapex.util.TimeUtils.now()
        val inMemory = synchronized(lock) {
            records.removeAll { now - it.completedAtEpochMs > SESSION_TTL_MS }
            records.firstOrNull { record ->
                record.transactionId == transactionId &&
                    (senderDeviceId.isBlank() || record.senderDeviceId.isBlank() || record.senderDeviceId.equals(senderDeviceId, ignoreCase = true)) &&
                    (expectedSize <= 0L || record.byteSize == expectedSize) &&
                    runCatching { java.io.File(record.finalPath).exists() || SystemFileSystem.exists(Path(record.finalPath)) }.getOrDefault(false)
            }
        }
        if (inMemory != null) return inMemory

        val persisted = com.fileapex.update.PendingUpdateStore.getTransactionRecord(transactionId)
        if (persisted != null &&
            (senderDeviceId.isBlank() || persisted.senderDeviceId.isBlank() || persisted.senderDeviceId.equals(senderDeviceId, ignoreCase = true)) &&
            (expectedSize <= 0L || persisted.byteSize == expectedSize) &&
            runCatching { java.io.File(persisted.finalPath).exists() || SystemFileSystem.exists(Path(persisted.finalPath)) }.getOrDefault(false)
        ) {
            synchronized(lock) { records.add(persisted) }
            return persisted
        }
        return null
    }

    fun findRecordByFilePath(filePath: String): TransferTransactionRecord? {
        if (filePath.isBlank()) return null
        val inMem = synchronized(lock) {
            records.firstOrNull { it.finalPath == filePath || it.targetPath == filePath }
        }
        if (inMem != null) return inMem
        return com.fileapex.update.PendingUpdateStore.findTransactionByFilePath(filePath)
    }

    fun markInstallAttempted(transactionId: String) {
        if (transactionId.isBlank()) return
        synchronized(lock) {
            val index = records.indexOfFirst { it.transactionId == transactionId }
            if (index >= 0) {
                records[index] = records[index].copy(installAttempted = true)
            }
        }
        com.fileapex.update.PendingUpdateStore.markTransactionInstallAttempted(transactionId)
    }

    fun markInstalled(transactionId: String) {
        if (transactionId.isBlank()) return
        synchronized(lock) {
            val index = records.indexOfFirst { it.transactionId == transactionId }
            if (index >= 0) {
                records[index] = records[index].copy(installAttempted = true, installed = true)
            }
        }
        com.fileapex.update.PendingUpdateStore.markTransactionInstalled(transactionId)
    }

    fun isInstalled(transactionId: String): Boolean {
        if (transactionId.isBlank()) return false
        val inMem = synchronized(lock) {
            records.firstOrNull { it.transactionId == transactionId }?.installed
        }
        if (inMem == true) return true
        return com.fileapex.update.PendingUpdateStore.isTransactionInstalled(transactionId)
    }

    fun purgeTransaction(transactionId: String) {
        if (transactionId.isBlank()) return
        synchronized(lock) {
            records.removeAll { it.transactionId == transactionId }
        }
        com.fileapex.update.PendingUpdateStore.purgeTransaction(transactionId)
    }

    fun purgeInstalledForSender(senderDeviceId: String) {
        if (senderDeviceId.isBlank()) return
        val toPurge = synchronized(lock) {
            records.filter { it.senderDeviceId == senderDeviceId && it.installed }
                .map { it.transactionId }
        }
        toPurge.forEach { purgeTransaction(it) }
    }
}

object TransferResumeProtocol {
    const val MIN_VERSION_CODE = 128
    const val OFFSET_QUERY = "offset"
    const val TOTAL_SIZE_QUERY = "totalSize"
    const val EXPECTED_SIZE_QUERY = "expectedSize"
    const val TRANSACTION_ID_QUERY = "txId"
    const val TIMESTAMP_QUERY = "txTimestamp"
    const val SENDER_DEVICE_ID_QUERY = "senderId"
    const val MAX_ATTEMPTS = 4
    const val RETRY_DELAY_MS = 750L

    fun parseByteOffset(queryOffset: String?, rangeHeader: String?): Long {
        queryOffset?.toLongOrNull()?.takeIf { it >= 0L }?.let { return it }
        return parseRangeStart(rangeHeader)
    }

    fun parseRangeStart(rangeHeader: String?): Long {
        val header = rangeHeader?.trim().orEmpty()
        if (header.isEmpty() || !header.startsWith("bytes=", ignoreCase = true)) return 0L
        val spec = header.substringAfter('=').substringBefore(',').trim()
        if (spec.startsWith('-')) return 0L
        return spec.substringBefore('-').trim().toLongOrNull()?.takeIf { it >= 0L } ?: 0L
    }

    fun parseContentRangeStart(contentRange: String?): Long {
        val header = contentRange?.trim().orEmpty()
        if (header.isEmpty() || !header.startsWith("bytes", ignoreCase = true)) return 0L
        val spec = header.substringAfter(' ').trim()
        if (spec.startsWith('*')) return 0L
        return spec.substringBefore('-').trim().toLongOrNull()?.takeIf { it >= 0L } ?: 0L
    }

    fun parseTotalSize(queryTotal: String?, contentRange: String?, sessionLength: Long?, offset: Long): Long? {
        queryTotal?.toLongOrNull()?.takeIf { it >= 0L }?.let { return it }
        parseContentRangeTotal(contentRange)?.let { return it }
        if (sessionLength != null && sessionLength >= 0L) {
            return offset + sessionLength
        }
        return null
    }

    fun parseContentRangeTotal(contentRange: String?): Long? {
        val header = contentRange?.trim().orEmpty()
        if (header.isEmpty()) return null
        val total = header.substringAfterLast('/').trim()
        if (total.isEmpty() || total == "*") return null
        return total.toLongOrNull()?.takeIf { it >= 0L }
    }

    fun inspectIncoming(
        preferredPath: String,
        expectedSize: Long,
        transactionId: String = "",
        senderDeviceId: String = ""
    ): ResumeOffsetResponse {
        if (transactionId.isNotBlank()) {
            val completedRecord = TransferTransactionJournal.findCompleted(transactionId, senderDeviceId, expectedSize)
            if (completedRecord != null) {
                return ResumeOffsetResponse(offset = completedRecord.byteSize, complete = true)
            }
        }
        val resolved = UniqueFileNames.resolve(preferredPath)
        val partPath = SocketFileStreamer.partPathFor(resolved)
        val onDisk = SocketFileStreamer.fileLength(partPath)
        if (expectedSize > 0L && onDisk > expectedSize) {
            return ResumeOffsetResponse(offset = 0L, complete = false)
        }
        val complete = expectedSize > 0L && onDisk == expectedSize
        if (complete) {
            SocketFileStreamer.finalizePart(partPath, resolved)
        }
        return ResumeOffsetResponse(offset = onDisk, complete = complete)
    }
}
