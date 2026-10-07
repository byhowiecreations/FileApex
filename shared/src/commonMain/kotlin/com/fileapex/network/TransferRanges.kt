package com.fileapex.network

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable

@Serializable
data class ByteSpan(val start: Long, val endExclusive: Long) {
    val length: Long get() = endExclusive - start
}

@Serializable
data class TransferCapabilities(
    val rangedStream: Boolean = false,
    val segmentedUpload: Boolean = false
)

@Serializable
data class SegmentStateResponse(
    val complete: Boolean = false,
    val ranges: List<ByteSpan> = emptyList(),
    /** Bytes the receiver has written for in-flight requests but not yet checkpointed into [ranges]. */
    val inFlightBytes: Long = 0L
)

/**
 * Live per-request byte counts for segment uploads still being written. The ledger only advances
 * every [TransferRuntime.CHECKPOINT_BYTES], so this is what lets a sender show what actually landed.
 */
internal object InFlightSegmentBytes {
    private val unrecorded = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<Long, Long>>()

    fun update(partPath: String, offset: Long, bytes: Long) {
        unrecorded.getOrPut(partPath) { java.util.concurrent.ConcurrentHashMap() }[offset] = bytes.coerceAtLeast(0L)
    }

    fun clear(partPath: String, offset: Long) {
        unrecorded[partPath]?.let { byOffset ->
            byOffset.remove(offset)
            if (byOffset.isEmpty()) unrecorded.remove(partPath, byOffset)
        }
    }

    fun sum(partPath: String): Long = unrecorded[partPath]?.values?.sum() ?: 0L
}

object TransferRanges {
    fun plan(totalSize: Long, segments: Int): List<ByteSpan> {
        require(totalSize >= 0L) { "Invalid size $totalSize" }
        val count = segments.coerceAtLeast(1).toLong().coerceAtMost(totalSize.coerceAtLeast(1L)).toInt()
        val base = totalSize / count
        return (0 until count).map { index ->
            val start = index * base
            val end = if (index == count - 1) totalSize else (index + 1) * base
            ByteSpan(start, end)
        }
    }

    fun merge(spans: List<ByteSpan>): List<ByteSpan> {
        val sorted = spans.filter { it.endExclusive > it.start }.sortedBy { it.start }
        if (sorted.isEmpty()) return emptyList()
        val out = ArrayList<ByteSpan>(sorted.size)
        var current = sorted.first()
        for (span in sorted.drop(1)) {
            current = if (span.start <= current.endExclusive) {
                ByteSpan(current.start, maxOf(current.endExclusive, span.endExclusive))
            } else {
                out += current
                span
            }
        }
        out += current
        return out
    }

    /** First byte inside [segment] that is not yet covered by [completed]. */
    fun resumePoint(segment: ByteSpan, completed: List<ByteSpan>): Long {
        val containing = merge(completed).firstOrNull { it.start <= segment.start && it.endExclusive > segment.start }
            ?: return segment.start
        return minOf(containing.endExclusive, segment.endExclusive)
    }

    fun covers(completed: List<ByteSpan>, totalSize: Long): Boolean {
        if (totalSize <= 0L) return true
        val merged = merge(completed)
        return merged.size == 1 && merged[0].start <= 0L && merged[0].endExclusive >= totalSize
    }

    fun coveredBytes(completed: List<ByteSpan>): Long = merge(completed).sumOf { it.length }
}

/**
 * Sidecar next to a `.fileapex-segpart` file listing byte ranges already flushed to disk.
 * Writers must force file data before calling [record] so a crash never claims bytes that
 * were still in the page cache.
 */
object RangeLedger {
    private const val SUFFIX = ".fileapex-ranges"
    private const val TOTAL_PREFIX = "total="
    private val lock = Any()

    fun ledgerPathFor(partPath: String): String = "$partPath$SUFFIX"

    /** Drops a stale part (different size, missing ledger, or ledger past the file end). */
    fun prepare(partPath: String, totalSize: Long): List<ByteSpan> = synchronized(lock) {
        val part = File(partPath)
        val ledger = File(ledgerPathFor(partPath))
        val stored = readLocked(ledger)
        if (stored != null &&
            stored.first == totalSize &&
            part.isFile &&
            stored.second.all { it.start >= 0L && it.endExclusive <= part.length() }
        ) {
            return@synchronized stored.second
        }
        runCatching { part.delete() }
        runCatching { ledger.delete() }
        emptyList()
    }

    fun read(partPath: String, totalSize: Long): List<ByteSpan> = synchronized(lock) {
        val stored = readLocked(File(ledgerPathFor(partPath))) ?: return@synchronized emptyList()
        if (stored.first != totalSize) emptyList() else stored.second
    }

    fun record(partPath: String, totalSize: Long, span: ByteSpan) {
        if (span.length <= 0L) return
        synchronized(lock) {
            val ledger = File(ledgerPathFor(partPath))
            val existing = readLocked(ledger)?.takeIf { it.first == totalSize }?.second.orEmpty()
            val merged = TransferRanges.merge(existing + span)
            val text = buildString {
                append(TOTAL_PREFIX).append(totalSize).append('\n')
                for (range in merged) {
                    append(range.start).append('-').append(range.endExclusive).append('\n')
                }
            }
            val temp = File("${ledger.path}.tmp")
            temp.writeText(text)
            runCatching {
                Files.move(
                    temp.toPath(),
                    ledger.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            }.recoverCatching {
                Files.move(temp.toPath(), ledger.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
        }
    }

    fun delete(partPath: String) {
        synchronized(lock) {
            runCatching { File(ledgerPathFor(partPath)).delete() }
            runCatching { File("${ledgerPathFor(partPath)}.tmp").delete() }
        }
    }

    private fun readLocked(ledger: File): Pair<Long, List<ByteSpan>>? {
        if (!ledger.isFile) return null
        val lines = runCatching { ledger.readLines() }.getOrNull() ?: return null
        val total = lines.firstOrNull()
            ?.takeIf { it.startsWith(TOTAL_PREFIX) }
            ?.removePrefix(TOTAL_PREFIX)
            ?.trim()
            ?.toLongOrNull()
            ?: return null
        val spans = lines.drop(1).mapNotNull { line ->
            val start = line.substringBefore('-').trim().toLongOrNull()
            val end = line.substringAfter('-', "").trim().toLongOrNull()
            if (start == null || end == null || end <= start) null else ByteSpan(start, end)
        }
        return total to TransferRanges.merge(spans)
    }
}
