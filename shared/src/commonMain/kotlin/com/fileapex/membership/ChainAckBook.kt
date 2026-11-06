package com.fileapex.membership

/**
 * Remembers, for a few minutes, which devices have not yet shown that they hold a change this device announced. An answer
 * to the announcement, or a later push showing a chain that contains the change, is the ack. The book only schedules
 * retries and writes one line (counts only); nothing depends on it, so a restart that loses it changes no outcome: a
 * device that missed the change learns it on its next contact with any member that is ahead.
 */
class ChainAckBook(
    private val now: () -> Long,
    /** True when [theirHead] is on this device's chain at or after the record [change]. */
    private val descends: suspend (change: String, theirHead: String) -> Boolean,
    private val report: (String) -> Unit = {}
) {
    private class Entry(val change: String, val startedAt: Long, val total: Int, val waiting: MutableSet<String>, val removed: Set<String>)

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    /** [removed] are devices the change removes: they wipe instead of holding the chain, so any answer from one counts. */
    fun expect(change: String, peers: Set<String>, removed: Set<String> = emptySet()) {
        if (peers.isEmpty()) return
        synchronized(lock) {
            entries[change] = Entry(change, now(), peers.size, peers.toMutableSet(), removed)
            while (entries.size > MAX_CHANGES) entries.remove(entries.keys.first())
        }
    }

    suspend fun heard(peerId: String, theirHead: String) {
        val open = synchronized(lock) { entries.values.filter { peerId in it.waiting } }
        for (entry in open) if (peerId in entry.removed || descends(entry.change, theirHead)) close(entry, peerId)
    }

    fun answered(peerId: String) {
        val open = synchronized(lock) { entries.values.filter { peerId in it.waiting && peerId in it.removed } }
        for (entry in open) close(entry, peerId)
    }

    private fun close(entry: Entry, peerId: String) {
        val done = synchronized(lock) {
            entry.waiting.remove(peerId)
            (entry.waiting.isEmpty() && entries.remove(entry.change) != null)
        }
        if (done) report("cluster updated: ${entry.total} of ${entry.total} acknowledged")
    }

    fun pending(): Set<String> = synchronized(lock) { entries.values.flatMapTo(LinkedHashSet()) { it.waiting } }

    fun open(): Int = synchronized(lock) { entries.size }

    /** Drops what is older than the window and says how many devices had not answered. Returns how many changes were dropped. */
    fun expire(): Int {
        val old = synchronized(lock) {
            val cut = now() - WINDOW_MS
            entries.values.filter { it.startedAt <= cut }.also { list -> list.forEach { entries.remove(it.change) } }
        }
        old.forEach { report("cluster update: ${it.total - it.waiting.size} of ${it.total} acknowledged, ${it.waiting.size} not yet; they catch up on contact") }
        return old.size
    }

    companion object {
        const val WINDOW_MS = 5 * 60_000L
        const val MAX_CHANGES = 64
    }
}
