package com.fileapex.membership

import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The chain is read from the store once and then followed record by record; only a change that is not a plain extension reads it again. */
class ChainViewCacheTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private class CountingStore(private val inner: ChainStore = InMemoryChainStore()) : ChainStore {
        var reads = 0
        override suspend fun all(): List<ChainRecord> { reads++; return inner.all() }
        override suspend fun put(record: ChainRecord) = inner.put(record)
        override suspend fun clear() = inner.clear()
        override suspend fun remove(hashes: Set<String>) = inner.remove(hashes)
    }

    private val mac = Dev("mac")
    private val phone = Dev("phone")
    private val tablet = Dev("tablet")
    private val laptop = Dev("laptop")

    @Before
    fun clean() = ChainRecords.resetVerificationCacheForTest()

    private suspend fun MembershipChainLog.start() {
        append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
    }

    private suspend fun fresh(records: List<ChainRecord>): ChainView =
        MembershipChainLog(InMemoryChainStore()).also { it.ingest(records) }.view()

    @Test
    fun theStoreIsReadOnceHoweverOftenTheChainIsViewed() = runBlocking {
        val store = CountingStore()
        val log = MembershipChainLog(store)
        log.start()
        log.view()
        val before = store.reads
        repeat(30) { log.view() }
        assertEquals(before, store.reads)
    }

    @Test
    fun aWarmViewChecksNoSignatureAndReadsNoStore() = runBlocking {
        val store = CountingStore()
        val log = MembershipChainLog(store)
        log.start()
        log.view()
        val reads = store.reads
        val checks = ChainRecords.verificationsForTest()
        repeat(30) { log.view() }
        assertEquals(reads, store.reads)
        assertEquals(checks, ChainRecords.verificationsForTest())
    }

    @Test
    fun anAppendExtendsTheViewWithoutReadingTheStoreAgain() = runBlocking {
        val store = CountingStore()
        val log = MembershipChainLog(store)
        log.start()
        log.view()
        val reads = store.reads
        log.append(mac.signer, RecordKind.ADMIT, tablet.id, tablet.key, tablet.pin, "paired")
        val view = log.view()
        assertEquals(3L, view.state.seq)
        assertEquals(reads, store.reads)
        assertEquals(fresh(log.recordsAfter(0L)).state, view.state)
    }

    @Test
    fun anIngestOfTheNextRecordExtendsTheViewWithoutReadingTheStoreAgain() = runBlocking {
        val source = MembershipChainLog(InMemoryChainStore())
        source.start()
        val store = CountingStore()
        val log = MembershipChainLog(store)
        log.ingest(source.recordsAfter(0L))
        log.view()
        source.append(mac.signer, RecordKind.ADMIT, tablet.id, tablet.key, tablet.pin, "paired")
        val reads = store.reads
        log.ingest(source.recordsAfter(2L))
        val view = log.view()
        assertEquals(3L, view.state.seq)
        assertEquals(reads, store.reads)
        assertEquals(source.view().state, view.state)
    }

    @Test
    fun aForkOrAnOrphanIsReadFromTheStoreAndGivesWhatAFreshLogGives() = runBlocking {
        val log = MembershipChainLog(InMemoryChainStore())
        log.start()
        log.view()
        val parent = log.view().state
        val a = ChainRecords.sign(mac.signer, ChainRecord(parent.seq + 1, parent.head, RecordKind.ADMIT, mac.id, mac.key, tablet.id, tablet.key, tablet.pin, "paired"))
        val b = ChainRecords.sign(mac.signer, ChainRecord(parent.seq + 1, parent.head, RecordKind.ADMIT, mac.id, mac.key, laptop.id, laptop.key, laptop.pin, "paired"))
        val orphan = ChainRecords.sign(mac.signer, ChainRecord(9, "e".repeat(64), RecordKind.ADMIT, mac.id, mac.key, "ghost", mac.key, mac.pin, "paired"))
        log.ingest(listOf(a, b, orphan))
        val expected = fresh(log.recordsAfter(0L) + orphan)
        assertEquals(expected.state, log.view().state)
        assertEquals(MembershipChain.preferred(a, b).hash(), log.view().state.head)
    }

    @Test
    fun anOlderLoserThatArrivesLaterDoesNotMoveTheHead() = runBlocking {
        val log = MembershipChainLog(InMemoryChainStore())
        log.start()
        val parent = log.view().state
        val a = ChainRecords.sign(mac.signer, ChainRecord(parent.seq + 1, parent.head, RecordKind.ADMIT, mac.id, mac.key, tablet.id, tablet.key, tablet.pin, "paired"))
        log.ingest(listOf(a))
        log.view()
        val b = ChainRecords.sign(mac.signer, ChainRecord(parent.seq + 1, parent.head, RecordKind.ADMIT, mac.id, mac.key, laptop.id, laptop.key, laptop.pin, "paired"))
        log.ingest(listOf(b))
        assertEquals(MembershipChain.preferred(a, b).hash(), log.view().state.head)
    }

    @Test
    fun clearAndPruneDropTheCache() = runBlocking {
        val store = CountingStore()
        val log = MembershipChainLog(store) { mac.id }
        log.start()
        log.view()
        log.clear()
        assertEquals(0L, log.view().state.seq)
        log.start()
        val other = ChainRecords.sign(phone.signer, ChainRecord(1, "", RecordKind.GENESIS, phone.id, phone.key, phone.id, phone.key, phone.pin, "paired"))
        store.put(other)
        log.pruneOtherRoots()
        assertEquals(2L, log.view().state.seq)
        assertTrue(log.view().canonical.none { it.hash() == other.hash() })
    }

    @Test
    fun recordsInAnyOrderGiveTheViewAFreshLogGives() = runBlocking {
        val source = MembershipChainLog(InMemoryChainStore())
        source.start()
        source.append(mac.signer, RecordKind.ADMIT, tablet.id, tablet.key, tablet.pin, "paired")
        source.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        val all = source.recordsAfter(0L)
        val log = MembershipChainLog(InMemoryChainStore())
        all.reversed().forEach { log.ingest(listOf(it)); log.view() }
        assertEquals(source.view().state, log.view().state)
        assertEquals(source.view().canonical.map { it.hash() }, log.view().canonical.map { it.hash() })
    }
}
