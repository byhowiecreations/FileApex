package com.fileapex.domain.pairing

import com.fileapex.domain.peer.ClusterClock
import com.fileapex.util.sha256Hex

/**
 * A short fingerprint of the removal records a node holds, sent on its health answer. Equal fingerprints mean two
 * nodes agree on every removal, which is almost always, so nothing more is exchanged. A different one means one
 * node missed a removal, and the other side reads that node's records once (see [RemovalPullLedger]).
 *
 * The Go daemon computes the same value from the same records: every record inside the window as `id:stamp`, one per
 * line, sorted, SHA-256, first 16 hex characters; "0" when there are none.
 */
object RemovalFingerprint {
    const val NONE = "0"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val WINDOW_MS = 30L * DAY_MS

    /** Midnight UTC, 30 days back. Whole days keep two clocks that differ a little from disagreeing at the edge. */
    fun cutoff(nowMs: Long): Long = (nowMs / DAY_MS) * DAY_MS - WINDOW_MS

    /** Records a current build stamped, inside the window: the ones every node can hold and compare. */
    fun windowed(records: List<RemovedDeviceRecord>, nowMs: Long): List<RemovedDeviceRecord> {
        val since = cutoff(nowMs)
        return records.filter {
            it.membershipProtocol >= ClusterClock.MEMBERSHIP_PROTOCOL && it.membershipVersion() >= since
        }
    }

    fun of(records: List<RemovedDeviceRecord>): String {
        if (records.isEmpty()) return NONE
        val text = records
            .map { "${it.deviceId.trim()}:${it.membershipVersion()}\n" }
            .sorted()
            .joinToString("")
        return sha256Hex(text.encodeToByteArray()).take(16)
    }
}

/**
 * Reads a peer's removal records at most once per distinct fingerprint it reports. A peer holding a record this node
 * will never accept (or the reverse) keeps reporting the same fingerprint, and must not make this repeat.
 */
class RemovalPullLedger {
    private val pulled = HashMap<String, String>()

    @Synchronized
    fun shouldPull(peerId: String, theirs: String, ours: String): Boolean {
        if (theirs.isBlank() || theirs == ours) {
            pulled.remove(peerId)
            return false
        }
        if (pulled[peerId] == theirs) return false
        pulled[peerId] = theirs
        return true
    }

    /** The read failed: allow the next probe to try again. */
    @Synchronized
    fun forget(peerId: String) {
        pulled.remove(peerId)
    }
}
