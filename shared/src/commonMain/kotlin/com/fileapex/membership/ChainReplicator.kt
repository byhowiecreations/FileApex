package com.fileapex.membership

import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@kotlinx.serialization.Serializable
data class ChainPush(
    val records: List<ChainRecord>,
    val profiles: List<com.fileapex.domain.peer.PeerNodeState> = emptyList(),
    /** The pusher's chain head, so the answer carries only what the pusher lacks. Empty from older builds, which get the whole chain. */
    val head: String = ""
)

/**
 * [head] is the hash of the answering device's chain head; [records] is everything it holds, losing fork branches included.
 * [profiles] are names, addresses and ports (see [ChainProfiles]): they are not signed, so a reader takes them only for
 * devices the verified chain holds as active and only over a pinned connection.
 */
@kotlinx.serialization.Serializable
data class ChainAnswer(val head: String, val records: List<ChainRecord>, val profiles: List<com.fileapex.domain.peer.PeerNodeState> = emptyList())

/**
 * What a device tells the cluster about itself and what it takes in, kept apart from membership: a profile fills in
 * names, addresses, ports and keys for a device the chain already holds, and never decides who is a member or which
 * TLS pin is trusted.
 */
interface ChainProfiles {
    /** This device's own profile. */
    suspend fun own(): com.fileapex.domain.peer.PeerNodeState?

    /** This device's own profile followed by those of the active members it holds from direct contact, with no presence. */
    suspend fun outgoing(): List<com.fileapex.domain.peer.PeerNodeState>
    /**
     * Takes in profiles from the verified peer [from], only for devices in [activeIds] and never for this device. A
     * device's own profile (the one with [from] as its id) is what it says about itself; any other is a relayed copy.
     */
    suspend fun incoming(states: List<com.fileapex.domain.peer.PeerNodeState>, activeIds: Set<String>, from: String)

    object None : ChainProfiles {
        override suspend fun own(): com.fileapex.domain.peer.PeerNodeState? = null
        override suspend fun outgoing(): List<com.fileapex.domain.peer.PeerNodeState> = emptyList()
        override suspend fun incoming(states: List<com.fileapex.domain.peer.PeerNodeState>, activeIds: Set<String>, from: String) = Unit
    }
}

/** The wire between two devices. A null answer means the peer could not be reached. */
interface ChainTransport {
    suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>): ChainAnswer?

    /** [head] is this device's head; a transport that can carries it so the peer answers with what this device lacks. */
    suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>, head: String): ChainAnswer? =
        push(peerId, records, profiles)

    suspend fun pull(peerId: String): ChainAnswer?

    /** [since] is this device's head: a peer that knows it answers with the records after it. Older peers ignore it and send everything. */
    suspend fun pull(peerId: String, since: String): ChainAnswer? = pull(peerId)

    /** Asks an active member to admit this device with its signing key. The answer is the member's chain. */
    suspend fun join(peerId: String, key: String, tlsPin: String, profile: com.fileapex.domain.peer.PeerNodeState?): ChainAnswer?
}

@kotlinx.serialization.Serializable
data class ChainJoinRequest(val key: String, val tlsPin: String, val profile: com.fileapex.domain.peer.PeerNodeState? = null)

/**
 * Keeps the devices' chains equal. A new record is pushed at once to the devices it concerns; a device that was away
 * catches up by pulling on its next contact with anyone. Records prove themselves by signature, so what a peer sends is
 * checked and never believed on the strength of the connection.
 */
class ChainReplicator(
    private val log: MembershipChainLog,
    private val transport: ChainTransport,
    /** Called after records from a peer changed the chain, so the roster and the pin store can follow it. */
    private val onChanged: suspend () -> Unit = {},
    val profiles: ChainProfiles = ChainProfiles.None,
    /** Tracks which devices have shown they hold an announced change; null where nobody needs to know. */
    private val acks: ChainAckBook? = null,
    /** Runs the retries of a silent device after an announcement; null runs none (a test calls [retryPending] itself). */
    private val scope: kotlinx.coroutines.CoroutineScope? = null,
    /** Called once after an announcement reached out to at least one device: the signed hello that tells the LAN to look. */
    private val afterAnnounce: suspend () -> Unit = {},
    /** When the first and the second retry of a silent device run after an announcement. */
    private val retryDelaysMs: Pair<Long, Long> = RETRY_FIRST_MS to RETRY_SECOND_MS,
    /** One counts-only line for something that went wrong in the background; never a device name. */
    private val report: (String) -> Unit = {}
) {
    /** The head each peer last showed, from its answers. A hint only: a wrong one costs one more push. */
    private val peerHeads = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** [removed] are the devices the change removes; they get the notice like everyone else and any answer from one counts as its ack. */
    suspend fun announce(peerIds: List<String>, removed: Set<String> = emptySet()) {
        if (peerIds.isEmpty()) return
        acks?.expect(log.view().state.head, peerIds.toSet(), removed)
        val answers = coroutineScope { peerIds.map { id -> async { id to pushTo(id, peerHeads[id]) } }.awaitAll() }
        answers.forEach { (id, answer) -> answer?.let { acks?.heard(id, it.head) } }
        afterAnnounce()
        if (acks != null) scope?.launch {
            cancellableCatching {
                delay(retryDelaysMs.first)
                retryPending()
                delay(retryDelaysMs.second - retryDelaysMs.first)
                retryPending()
                delay(ChainAckBook.WINDOW_MS - retryDelaysMs.second)
                acks.expire()
            }.onFailure { report("chain retry stopped: ${it::class.simpleName}") }
        }
    }

    /** Asks the devices that have not shown the change again, with what they lack. */
    suspend fun retryPending() {
        val book = acks ?: return
        for (id in book.pending()) pushTo(id, peerHeads[id])?.let { book.heard(id, it.head) }
    }

    /**
     * Sends [peerId] what it lacks when its head is known, otherwise everything. When the answer shows the peer did not
     * reach this head the whole chain follows once, so a stale hint never leaves a peer behind.
     */
    private suspend fun pushTo(peerId: String, known: String?): ChainAnswer? {
        val own = profiles.outgoing()
        val mine = log.view().state.head
        val delta = known?.let { log.recordsAfterHead(it) }
        val first = transport.push(peerId, delta ?: log.recordsAfter(0L), own, mine)
        first?.let { peerHeads[peerId] = it.head }
        if (delta == null || first == null || first.head == mine) return first
        return transport.push(peerId, log.recordsAfter(0L), own, mine)?.also { peerHeads[peerId] = it.head }
    }

    /** True when the peer answered. Takes in what it has and sends it what it lacks. */
    suspend fun reconcile(peerId: String): Boolean {
        val theirs = transport.pull(peerId, log.view().state.head) ?: return false
        peerHeads[peerId] = theirs.head
        val before = log.view().state.head
        log.ingest(theirs.records)
        val after = log.view().state.head
        if (after != before) onChanged()
        takeProfiles(theirs.profiles, peerId)
        if (after != theirs.head) pushTo(peerId, theirs.head)
        else if (after != before) transport.push(peerId, emptyList(), emptyList(), after)
        return true
    }

    /**
     * The user paired this device into the cluster of [peerId] in person, so that cluster wins. When the peer's chain has
     * another root, [onReplacing] runs once and this device's own chain is dropped before the peer's is taken. [beforeApply] runs after the peer's chain is stored and before anything applies it, so history can be marked first.
     */
    suspend fun joinClusterOf(peerId: String, beforeApply: suspend () -> Unit = {}, onReplacing: suspend () -> Unit): Boolean {
        val theirs = transport.pull(peerId) ?: return false
        val theirRoot = theirs.records.firstOrNull { it.kind == RecordKind.GENESIS }?.hash()
        val mine = log.rootHash()
        if (mine != null && theirRoot != null && mine != theirRoot) {
            onReplacing()
            log.clear()
        }
        log.ingest(theirs.records)
        beforeApply()
        onChanged()
        takeProfiles(theirs.profiles, peerId)
        return true
    }

    /** Hands profiles that came with a verified answer or push to the profile book, for the devices the chain holds as active. */
    suspend fun takeProfiles(states: List<com.fileapex.domain.peer.PeerNodeState>, from: String) {
        if (states.isNotEmpty()) profiles.incoming(states, log.view().state.activeIds().toSet(), from)
    }

    /** An inbound push. */
    suspend fun receive(from: String, records: List<ChainRecord>, states: List<com.fileapex.domain.peer.PeerNodeState> = emptyList(), since: String = ""): ChainAnswer {
        val before = log.view().state.head
        log.ingest(records)
        if (log.view().state.head != before) onChanged()
        takeProfiles(states, from)
        if (since.isNotEmpty()) acks?.heard(from, since)
        return serve(since = since)
    }

    /** [withProfiles] is false for a caller that was removed: it may read the chain and learns nothing about the cluster. */
    suspend fun serve(withProfiles: Boolean = true, since: String = ""): ChainAnswer =
        log.recordsAfterHead(since).let { delta ->
            ChainAnswer(
                log.view().state.head,
                delta ?: log.recordsAfter(0L),
                // An answer that says nothing is new carries no profiles; a change or a first meeting does.
                if (!withProfiles || delta?.isEmpty() == true) emptyList() else profiles.outgoing()
            )
        }

    private companion object {
        const val RETRY_FIRST_MS = 5_000L
        const val RETRY_SECOND_MS = 30_000L
    }
}
