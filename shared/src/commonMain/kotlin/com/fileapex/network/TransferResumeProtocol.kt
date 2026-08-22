package com.fileapex.network

import com.fileapex.platform.UniqueFileNames
import kotlinx.serialization.Serializable

/**
 * Byte-offset handshake for on-demand LAN socket transfers (v0.8.2a).
 * Receiver reports how many bytes it already has on disk; sender seeks and streams the rest.
 */
@Serializable
data class ResumeOffsetResponse(
    val offset: Long,
    val complete: Boolean = false
)

object TransferResumeProtocol {
    const val MIN_VERSION_CODE = 128
    const val OFFSET_QUERY = "offset"
    const val TOTAL_SIZE_QUERY = "totalSize"
    const val EXPECTED_SIZE_QUERY = "expectedSize"
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

    fun inspectIncoming(preferredPath: String, expectedSize: Long): ResumeOffsetResponse {
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
