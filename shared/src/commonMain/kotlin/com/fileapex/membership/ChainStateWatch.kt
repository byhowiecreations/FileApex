package com.fileapex.membership

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlin.random.Random

/** Counts only: how often this device compared, pulled and healed. No device is named except in the flapping list by id. */
data class ChainSyncCounters(
    val equalReads: Long,
    val pulls: Map<String, Long>,
    val heals: Long,
    val flapping: Set<String>
)

/**
 * Decides, from the state field a peer shows on contact, whether this device needs to ask that peer for anything. Equal
 * state costs nothing. A peer that is ahead, or on another branch, is pulled once per distinct head and then on a
 * back-off table, so a fork never turns into a loop. The tick keeps its own duties and asks this class which peers it
 * still has to reach: those not heard from lately, on the same table.
 */
class ChainStateWatch(
    private val own: suspend () -> ChainStateField,
    private val pull: suspend (peerId: String) -> Boolean,
    private val now: () -> Long = com.fileapex.util.TimeUtils::now,
    private val jitterMs: () -> Long = { Random.nextLong(0, MAX_JITTER_MS) },
    private val report: (String) -> Unit = {}
) {
    private class Attempt(var count: Int, var nextAt: Long)

    private class Peer {
        var heardAt = 0L
        var lastPullAt = 0L
        var tickFailures = 0
        var tickNextAt = 0L
        var field: ChainStateField? = null
        val attempts = LinkedHashMap<String, Attempt>()
        val changes = ArrayDeque<Long>()
    }

    private val lock = Any()
    private val peers = LinkedHashMap<String, Peer>()
    private val flapping = LinkedHashSet<String>()
    private val slots = Semaphore(MAX_PULLS)
    private val pulls = LinkedHashMap<String, Long>()
    private var equalReads = 0L
    private var heals = 0L
    private var netAt: Long? = null

    private fun peer(id: String): Peer = peers.getOrPut(id) { Peer() }.also {
        while (peers.size > MAX_PEERS) peers.remove(peers.keys.first())
    }

    private fun count(reason: String) = synchronized(lock) { pulls[reason] = (pulls[reason] ?: 0L) + 1L }

    /** A peer showed [theirs]; null means it showed none (an older build), which only the tick and the safety net cover. */
    suspend fun note(peerId: String, theirs: ChainStateField?) {
        if (theirs == null) return
        val mine = own()
        val t = now()
        val head = synchronized(lock) { decide(peerId, theirs, mine, t) } ?: return
        try {
            val wait = jitterMs()
            if (wait > 0L) delay(wait)
            count("state")
            val answered = pull(peerId)
            if (answered && own().let { it.seq == theirs.seq && it.head == theirs.head }) {
                synchronized(lock) { peers[peerId]?.attempts?.remove(head) }
            }
        } finally {
            slots.release()
        }
    }

    /** Called with the lock held. Returns the head to pull and a taken pull slot, or null for nothing to do. */
    private fun decide(peerId: String, theirs: ChainStateField, mine: ChainStateField, t: Long): String? {
        val p = peer(peerId)
        val before = p.field
        p.field = theirs
        p.heardAt = t
        if (before != null && before != theirs) {
            p.changes.addLast(t)
            while (p.changes.isNotEmpty() && t - p.changes.first() > HOUR_MS) p.changes.removeFirst()
            if (p.changes.size > FLAP_LIMIT && flapping.add(peerId)) report("chain state of a peer changes too often: handled on the back-off table only")
        }
        val sameChain = theirs.seq == mine.seq && theirs.head == mine.head
        if (sameChain) equalReads++
        if (theirs.seq == 0L || sameChain || theirs.seq < mine.seq) return null
        if (t - p.lastPullAt < MIN_GAP_MS) return null
        val attempt = p.attempts[theirs.head]
        val flaps = peerId in flapping
        if (attempt != null && t < attempt.nextAt) return null
        if (attempt == null && flaps && t < p.lastPullAt + BACKOFF_MS[0]) return null
        if (!slots.tryAcquire()) return null
        p.lastPullAt = t
        val a = attempt ?: Attempt(0, 0L).also {
            p.attempts[theirs.head] = it
            while (p.attempts.size > MAX_ATTEMPTS) p.attempts.remove(p.attempts.keys.first())
        }
        a.count++
        a.nextAt = t + BACKOFF_MS[minOf(a.count, BACKOFF_MS.size) - 1]
        return theirs.head
    }

    /**
     * The peers the tick reached: those heard from within the contact window count without a request, the others are
     * asked once their back-off allows. A peer that shows no state (an older build, a removed device, a handshake that
     * fails) is reached this way, on 1, 5, 30 minutes and then hourly while it does not answer.
     */
    suspend fun tickPeers(ids: Collection<String>): Set<String> {
        val answering = LinkedHashSet<String>()
        for (id in ids) {
            val t = now()
            val due = synchronized(lock) {
                val p = peer(id)
                when {
                    p.heardAt > 0L && t - p.heardAt <= CONTACT_WINDOW_MS -> { answering += id; false }
                    else -> t >= p.tickNextAt
                }
            }
            if (!due) continue
            count("tick")
            val ok = pull(id)
            synchronized(lock) {
                val p = peer(id)
                if (ok) {
                    p.heardAt = now()
                    p.tickFailures = 0
                    p.tickNextAt = 0L
                    answering += id
                } else {
                    p.tickFailures++
                    p.tickNextAt = now() + BACKOFF_MS[minOf(p.tickFailures, BACKOFF_MS.size) - 1]
                }
            }
        }
        return answering
    }

    /**
     * The peer did not answer a health probe. One that was heard within the contact window loses that contact, so the next tick asks
     * it: a key change or a removal that a missed push left unknown is then found within a minute, as before. One that
     * has been away for a while stays on the back-off table, so a device that is off does not get a request every sweep.
     */
    fun unreached(peerId: String) = synchronized(lock) {
        peers[peerId]?.let { p ->
            if (p.heardAt > 0L && now() - p.heardAt <= CONTACT_WINDOW_MS) {
                p.heardAt = 0L
                p.tickNextAt = 0L
                p.tickFailures = 0
            }
        }
        Unit
    }

    fun safetyNetDue(): Boolean = synchronized(lock) { netAt?.let { now() - it >= SAFETY_NET_MS } ?: true }

    /** Asks every peer once, whatever it showed. A pull that changes this device's chain means a push or a state read was missed. */
    suspend fun runSafetyNet(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(lock) { netAt = now() }
        for (id in ids) {
            val before = own()
            count("net")
            if (pull(id) && own().let { it.seq != before.seq || it.head != before.head }) {
                synchronized(lock) { heals++ }
                report("chain safety net found a change that no push or state read had brought")
            }
        }
    }

    fun counters(): ChainSyncCounters = synchronized(lock) { ChainSyncCounters(equalReads, pulls.toMap(), heals, flapping.toSet()) }

    fun trackedPeers(): Int = synchronized(lock) { peers.size }

    fun summary(): String = counters().let {
        "chain sync: equal reads ${it.equalReads}, pulls ${it.pulls.entries.joinToString(",") { e -> "${e.key}=${e.value}" }}, " +
            "heals ${it.heals}, flapping ${it.flapping.size}, signature checks ${ChainRecords.verifications()}"
    }

    companion object {
        const val MAX_PEERS = 128
        private const val MAX_PULLS = 2
        private const val MAX_ATTEMPTS = 8
        private const val MAX_JITTER_MS = 500L
        private const val MIN_GAP_MS = 10_000L
        private const val FLAP_LIMIT = 6
        private const val HOUR_MS = 3_600_000L
        private const val CONTACT_WINDOW_MS = 12 * 60_000L
        private const val SAFETY_NET_MS = 6 * HOUR_MS
        private val BACKOFF_MS = longArrayOf(60_000L, 5 * 60_000L, 30 * 60_000L, HOUR_MS)
    }
}
