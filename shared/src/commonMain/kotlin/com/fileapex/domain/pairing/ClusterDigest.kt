package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.util.sha256Hex

/**
 * One short value for "this node holds the same cluster": every roster row and every removal record inside the
 * fingerprint window as `id:stamp:state` (state `a` active, `r` removed), one per line, sorted, SHA-256, first 16 hex
 * characters. Equal digests mean two nodes agree on the roster and on every removal. A different one makes the
 * prober read that peer once (see [RemovalPullLedger]).
 */
object ClusterDigest {
    const val NONE = "0"
    const val ACTIVE = 'a'
    const val REMOVED = 'r'

    data class Row(val deviceId: String, val stamp: Long, val state: Char)

    fun of(rows: List<Row>): String {
        if (rows.isEmpty()) return NONE
        val text = rows.map { "${it.deviceId.trim()}:${it.stamp}:${it.state}\n" }.sorted().joinToString("")
        return sha256Hex(text.encodeToByteArray()).take(16)
    }

    /** This device at its own stamp, the active rows of [roster], and [removals] (already inside the window). */
    fun rows(
        selfId: String,
        selfStamp: Long,
        roster: List<PairedDeviceEntity>,
        removals: List<RemovedDeviceRecord>
    ): List<Row> {
        val self = selfId.trim()
        val active = roster.filter { !it.isRemoved && it.deviceId.trim() != self }
            .map { Row(it.deviceId, it.clusterVersion, ACTIVE) }
        val removed = removals.map { Row(it.deviceId, it.membershipVersion(), REMOVED) }
        return listOf(Row(self, selfStamp, ACTIVE)) + active + removed
    }
}
