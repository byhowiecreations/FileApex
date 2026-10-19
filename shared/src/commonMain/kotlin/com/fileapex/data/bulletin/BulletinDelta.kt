package com.fileapex.data.bulletin

/**
 * Bulletin changes since a cursor, for a device that was away. Online peers get live pushes; an offline peer
 * pulls what it missed from one online peer when it returns. Deletes travel as tombstones.
 */
object BulletinDelta {
    const val TOMBSTONE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    const val PAGE_LIMIT = 200

    data class Change(val timeMs: Long, val item: BulletinSyncItem)

    data class Page(val items: List<BulletinSyncItem>, val cursor: Long, val more: Boolean)

    /** A cursor older than the tombstone retention may have missed deletes whose tombstones are gone: send everything. */
    fun isStale(since: Long, nowMs: Long): Boolean = since > 0L && nowMs - since > TOMBSTONE_RETENTION_MS

    fun page(changes: List<Change>, since: Long, limit: Int = PAGE_LIMIT): Page {
        val ordered = changes.sortedBy { it.timeMs }
        val sent = ordered.take(limit)
        if (sent.isEmpty()) return Page(emptyList(), since, false)
        val more = ordered.size > limit
        var cursor = sent.last().timeMs
        // A whole page at one instant would repeat for ever; step past it so the next request moves on.
        if (more && cursor <= since) cursor = since + 1
        return Page(sent.map { it.item }, cursor, more)
    }

    /** 0.19.1 added the delta endpoint; older builds, a blank version and Docker nodes do not have it. */
    fun supportedBy(clientVersion: String, clientVersionCode: Int): Boolean {
        val code = if (clientVersionCode >= 1_000_000) clientVersionCode % 1000 else clientVersionCode
        if (code >= MIN_VERSION_CODE) return true
        val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)").find(clientVersion.trim()) ?: return false
        val (major, minor, patch) = match.destructured
        val m = major.toInt()
        val n = minor.toInt()
        return m > 0 || n > 19 || (n == 19 && patch.toInt() >= 1)
    }

    /** Offline peers that can pull get nothing queued; older builds cannot pull, so their outbox stays. */
    fun queueForPeer(supportsDelta: Boolean, isOnline: Boolean): Boolean = isOnline || !supportsDelta

    /** The peer's bulletin version (its latest change, on its clock) is ahead of how far we have pulled from it. */
    fun shouldPull(peerVersion: Long, cursor: Long): Boolean = peerVersion > cursor

    /** The cursor is ahead of anything the peer has (its database was reset): start over with a full snapshot. */
    fun isSnapshot(since: Long, latestChange: Long, nowMs: Long): Boolean =
        since <= 0L || isStale(since, nowMs) || since > latestChange

    private const val MIN_VERSION_CODE = 207
}
