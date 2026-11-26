package com.fileapex.network.routes

import com.fileapex.di.FileApexServices
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.network.RangeLedger
import com.fileapex.network.TransferRanges
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val inboundUploadSeq = AtomicLong(0L)

private suspend fun resolveInboundPeerName(senderId: String): String {
    val trimmed = senderId.trim()
    if (trimmed.isEmpty() || !FileApexServices.isDatabaseReady()) return ""
    return withContext(Dispatchers.IO) {
        FileApexServices.deviceRepository.getDevice(trimmed)?.deviceName.orEmpty()
    }
}

/** Reports keyed inbound upload progress so receives show bytes, percentage, speed and ETA. */
internal suspend fun <T> trackInboundUpload(
    fileName: String,
    senderId: String,
    keySeed: String,
    offset: Long,
    totalBytes: Long,
    block: suspend (onBytes: (Long) -> Unit) -> T
): T {
    val streamKey = "recv:$keySeed:${inboundUploadSeq.incrementAndGet()}"
    val peerName = resolveInboundPeerName(senderId)
    TransferActivityGuard.beginTransfer(
        fileName = fileName,
        destinationDeviceName = peerName,
        streamKey = streamKey,
        isReceive = true
    )
    if (totalBytes > 0L) {
        TransferActivityGuard.updateProgress(offset, totalBytes, streamKey = streamKey)
    }
    return try {
        block { received ->
            if (totalBytes > 0L) {
                TransferActivityGuard.updateProgress(offset + received, totalBytes, streamKey = streamKey)
            }
        }
    } finally {
        TransferActivityGuard.endTransfer(streamKey = streamKey)
    }
}

/** Aggregates concurrent upload-segment writers for one part file into a single receive stream. */
internal object InboundSegmentProgress {
    private val reqSeq = AtomicLong(0L)
    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    private class Entry(
        val streamKey: String,
        val baseCovered: Long,
        val totalSize: Long
    ) {
        var refCount: Int = 0
        var finishedDelta: Long = 0L
        val inFlight = HashMap<Long, Long>()

        fun totalDone(): Long = (baseCovered + finishedDelta + inFlight.values.sum()).coerceIn(0L, totalSize)
    }

    suspend fun <T> trackSegment(
        partPath: String,
        fileName: String,
        senderId: String,
        totalSize: Long,
        block: suspend (onBytes: (Long) -> Unit) -> T
    ): T {
        val reqId = reqSeq.incrementAndGet()
        val peerName = resolveInboundPeerName(senderId)
        val (entry, isFirst, initialDone) = synchronized(lock) {
            val existing = entries[partPath]
            if (existing == null) {
                val baseCovered = TransferRanges.coveredBytes(RangeLedger.read(partPath, totalSize))
                val created = Entry(
                    streamKey = "recv-seg:${reqSeq.incrementAndGet()}",
                    baseCovered = baseCovered,
                    totalSize = totalSize
                )
                created.refCount = 1
                entries[partPath] = created
                Triple(created, true, created.totalDone())
            } else {
                existing.refCount += 1
                Triple(existing, false, existing.totalDone())
            }
        }
        if (isFirst) {
            TransferActivityGuard.beginTransfer(
                fileName = fileName,
                destinationDeviceName = peerName,
                streamKey = entry.streamKey,
                isReceive = true
            )
            if (totalSize > 0L) {
                TransferActivityGuard.updateProgress(initialDone, totalSize, streamKey = entry.streamKey)
            }
        }
        return try {
            block { received ->
                val done = synchronized(lock) {
                    entry.inFlight[reqId] = received
                    entry.totalDone()
                }
                if (totalSize > 0L) {
                    TransferActivityGuard.updateProgress(done, totalSize, streamKey = entry.streamKey)
                }
            }
        } finally {
            val (isLast, finalDone) = synchronized(lock) {
                val written = entry.inFlight.remove(reqId) ?: 0L
                entry.finishedDelta += written
                entry.refCount -= 1
                val last = entry.refCount <= 0
                if (last) entries.remove(partPath)
                last to entry.totalDone()
            }
            if (isLast) {
                TransferActivityGuard.endTransfer(streamKey = entry.streamKey)
            } else if (totalSize > 0L) {
                TransferActivityGuard.updateProgress(finalDone, totalSize, streamKey = entry.streamKey)
            }
        }
    }
}
