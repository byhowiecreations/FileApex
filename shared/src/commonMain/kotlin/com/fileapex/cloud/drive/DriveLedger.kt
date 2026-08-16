package com.fileapex.cloud.drive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DriveLedger(
    val version: Int = 1,
    val entries: List<DriveLedgerEntry> = emptyList()
)

@Serializable
data class DriveTargetStatus(
    val deviceId: String,
    val status: String,
    val updatedAtEpochMs: Long = 0L
)

@Serializable
data class DriveLedgerEntry(
    val entryId: String,
    val uploadedAtEpochMs: Long,
    val sourceDeviceId: String,
    val driveFileId: String,
    val contentHash: String,
    val fileName: String,
    val sizeBytes: Long,
    /** `broadcast` for Notes attachments; otherwise the destination device ID. */
    val targetScope: String,
    val kind: String,
    val retrievedBy: List<String> = emptyList(),
    val delivery: List<DriveTargetStatus> = emptyList(),
    val pinned: Boolean = false,
    val relativeDestPath: String = "",
    val noteId: String = ""
) {
    fun isRetrievedBy(deviceId: String): Boolean {
        if (deviceId.isBlank()) return false
        if (deviceId in retrievedBy) return true
        return delivery.any { status ->
            status.deviceId == deviceId && status.status == DriveDeliveryStates.RETRIEVED
        }
    }

    fun markPending(deviceId: String, nowEpochMs: Long): DriveLedgerEntry =
        withDelivery(deviceId, DriveDeliveryStates.PENDING_SYNC, nowEpochMs)

    fun markRetrieved(deviceId: String, nowEpochMs: Long): DriveLedgerEntry {
        val next = withDelivery(deviceId, DriveDeliveryStates.RETRIEVED, nowEpochMs)
        return next.copy(retrievedBy = (next.retrievedBy + deviceId).distinct())
    }

    fun isDirectTransferComplete(): Boolean =
        kind == DriveLedgerKinds.FILE_TRANSFER &&
            targetScope != DriveLedgerScope.BROADCAST &&
            isRetrievedBy(targetScope)

    private fun withDelivery(
        deviceId: String,
        status: String,
        nowEpochMs: Long
    ): DriveLedgerEntry {
        val row = DriveTargetStatus(deviceId, status, nowEpochMs)
        val others = delivery.filterNot { it.deviceId == deviceId }
        return copy(delivery = others + row)
    }
}

object DriveLedgerKinds {
    const val NOTE_ATTACHMENT = "note_attachment"
    const val FILE_TRANSFER = "file_transfer"
}

object DriveLedgerScope {
    const val BROADCAST = "broadcast"
}

object DriveDeliveryStates {
    const val PENDING_SYNC = "pending_sync"
    const val RETRIEVED = "retrieved"
}

data class DriveLedgerSnapshot(
    val ledger: DriveLedger,
    val etag: String?,
    val logFileId: String,
    val notModified: Boolean = false
)

data class DriveUploadedFile(
    val id: String,
    val sizeBytes: Long,
    val contentHash: String
)

object DriveLedgerCodec {
    const val LOG_FILE_NAME = "log.md"
    const val MARKER = "<!-- fileapex-ledger-v1 -->"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun parse(markdown: String): DriveLedger {
        val fenced = extractJsonFence(markdown) ?: return DriveLedger()
        return runCatching { json.decodeFromString(DriveLedger.serializer(), fenced) }
            .getOrDefault(DriveLedger())
    }

    fun render(ledger: DriveLedger): String = buildString {
        appendLine("# FileApex Relay Ledger")
        appendLine()
        appendLine(MARKER)
        appendLine()
        appendLine("Do not edit by hand. FileApex uses this file as the Drive relay index.")
        appendLine()
        appendLine("```json")
        appendLine(json.encodeToString(DriveLedger.serializer(), ledger))
        appendLine("```")
    }

    fun upsert(ledger: DriveLedger, entry: DriveLedgerEntry): DriveLedger {
        val without = ledger.entries.filterNot {
            it.entryId == entry.entryId ||
                (it.contentHash == entry.contentHash && it.targetScope == entry.targetScope)
        }
        return ledger.copy(entries = without + entry)
    }

    private fun extractJsonFence(markdown: String): String? {
        val startToken = "```json"
        val start = markdown.indexOf(startToken)
        if (start < 0) return null
        val jsonStart = start + startToken.length
        val end = markdown.indexOf("```", jsonStart)
        if (end < 0) return null
        return markdown.substring(jsonStart, end).trim()
    }
}
