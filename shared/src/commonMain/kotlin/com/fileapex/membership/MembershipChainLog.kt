package com.fileapex.membership

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Every record this device has seen, the losing sides of a fork included. Order and meaning come from [MembershipChain], not from the store. */
interface ChainStore {
    suspend fun all(): List<ChainRecord>

    /** True when the record was new. */
    suspend fun put(record: ChainRecord): Boolean

    /** Forgets every record: this device left or was removed and its next pairing starts from the chain the new cluster holds. */
    suspend fun clear()

    /** Deletes the records with these hashes; returns how many were stored. */
    suspend fun remove(hashes: Set<String>): Int
}

class InMemoryChainStore : ChainStore {
    private val rows = LinkedHashMap<String, ChainRecord>()
    private val mutex = Mutex()

    override suspend fun all(): List<ChainRecord> = mutex.withLock { rows.values.toList() }

    override suspend fun put(record: ChainRecord): Boolean = mutex.withLock { rows.putIfAbsent(record.hash(), record) == null }

    override suspend fun clear() = mutex.withLock { rows.clear() }

    override suspend fun remove(hashes: Set<String>): Int = mutex.withLock { hashes.count { rows.remove(it) != null } }
}

/** The membership after walking the chain from its genesis, and the records that make it up. */
data class ChainView(val state: ChainState, val canonical: List<ChainRecord>)

/**
 * The membership log of this device: appends its own signed records, takes in records from other devices in any order,
 * and answers with the one chain every device that holds the same records agrees on.
 */
class MembershipChainLog(private val store: ChainStore, private val selfId: () -> String? = { null }) {
    private val writer = Mutex()

    /**
     * The one genesis this device follows: the one it wrote, otherwise the lower hash. A second genesis is another
     * cluster and never competes with it; only a database that already holds two (from before this rule) has to choose.
     */
    private fun rootOf(all: List<ChainRecord>): ChainRecord? {
        val geneses = all.filter { it.kind == RecordKind.GENESIS && it.parentHash == ChainState.EMPTY.head }
        val self = selfId()
        return geneses.firstOrNull { it.actor == self } ?: geneses.minByOrNull { it.hash() }
    }

    /** Hash of the genesis this device follows, or null when it holds no chain. */
    suspend fun rootHash(): String? = rootOf(store.all())?.hash()

    /** Walks from the root. At each step the records that are valid there compete, and [MembershipChain.preferred] picks one. */
    suspend fun view(): ChainView {
        val all = store.all()
        val byParent = all.groupBy { it.parentHash }
        val root = rootOf(all)
        var state = ChainState.EMPTY
        val chain = mutableListOf<ChainRecord>()
        while (true) {
            val here = state
            val candidates = if (here.head == ChainState.EMPTY.head) listOfNotNull(root) else byParent[here.head].orEmpty()
            val next = candidates
                .mapNotNull { record -> (MembershipChain.apply(here, record) as? ChainVerdict.Accepted)?.let { record to it.state } }
                .reduceOrNull { a, b -> if (MembershipChain.preferred(a.first, b.first) === a.first) a else b }
                ?: break
            chain += next.first
            state = next.second
        }
        return ChainView(state, chain)
    }

    /** Builds, signs and stores a record on top of the current chain. Rejected records are not stored. */
    suspend fun append(
        signer: EventSigner,
        kind: RecordKind,
        subject: String,
        subjectKey: String,
        tlsPin: String,
        reason: String
    ): ChainVerdict = writer.withLock {
        val state = view().state
        val record = ChainRecords.sign(
            signer,
            ChainRecord(state.seq + 1, state.head, kind, signer.deviceId, signer.publicKey, subject, subjectKey, tlsPin, reason)
        )
        val verdict = MembershipChain.apply(state, record)
        if (verdict is ChainVerdict.Accepted) store.put(record)
        verdict
    }

    /** One record to write, without its place in the chain. */
    class Draft(val kind: RecordKind, val subject: String, val subjectKey: String, val tlsPin: String, val reason: String)

    /** Writes [drafts] one after the other as one step: if any of them is refused none is stored. */
    suspend fun appendAll(signer: EventSigner, drafts: List<Draft>): ChainVerdict = writer.withLock {
        var state = view().state
        val records = mutableListOf<ChainRecord>()
        for (d in drafts) {
            val record = ChainRecords.sign(
                signer,
                ChainRecord(state.seq + 1, state.head, d.kind, signer.deviceId, signer.publicKey, d.subject, d.subjectKey, d.tlsPin, d.reason)
            )
            when (val verdict = MembershipChain.apply(state, record)) {
                is ChainVerdict.Accepted -> { state = verdict.state; records += record }
                is ChainVerdict.Rejected -> return@withLock verdict
            }
        }
        records.forEach { store.put(it) }
        ChainVerdict.Accepted(state)
    }

    /**
     * Stores records from another device whose signature holds. Whether they belong to the chain is decided when it is
     * walked, so a record that arrives before its parent waits. Returns how many were new.
     */
    suspend fun ingest(records: List<ChainRecord>): Int = writer.withLock {
        var added = 0
        for (record in records.filter { ChainRecords.verify(it) }) {
            if (record.kind == RecordKind.GENESIS && store.all().any { it.kind == RecordKind.GENESIS }) continue
            if (store.put(record)) added++
        }
        added
    }

    suspend fun clear() = writer.withLock { store.clear() }

    /**
     * Deletes the records of any genesis that is not this device's root, with everything built on it. A database made
     * before the one-root rule holds another cluster's records; they are never served and never walked. A record whose
     * parent is unknown is kept: it may only be waiting for its parent.
     */
    suspend fun pruneOtherRoots(): Int = writer.withLock {
        val all = store.all()
        val root = rootOf(all) ?: return@withLock 0
        val byParent = all.groupBy { it.parentHash }
        val queue = ArrayDeque(all.filter { it.kind == RecordKind.GENESIS && it.parentHash == ChainState.EMPTY.head && it.hash() != root.hash() })
        val doomed = HashSet<String>()
        while (queue.isNotEmpty()) {
            val record = queue.removeFirst()
            if (doomed.add(record.hash())) queue += byParent[record.hash()].orEmpty()
        }
        if (doomed.isEmpty()) 0 else store.remove(doomed)
    }

    /** Every stored record of this device's cluster above [seq], for a device that is behind. Losing branches are included so a fork resolves everywhere; another cluster's records are not. */
    suspend fun recordsAfter(seq: Long): List<ChainRecord> {
        val all = store.all()
        val byParent = all.groupBy { it.parentHash }
        val under = ArrayList<ChainRecord>()
        val queue = ArrayDeque(listOfNotNull(rootOf(all)))
        while (queue.isNotEmpty()) {
            val record = queue.removeFirst()
            under += record
            queue += byParent[record.hash()].orEmpty()
        }
        return under.filter { it.seq > seq }.sortedBy { it.seq }
    }
}
