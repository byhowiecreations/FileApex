package com.fileapex.membership

import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Databases made before the one-root rule still hold another cluster's records; they are removed once, and nothing waiting for a parent goes with them. */
class SecondRootPruneTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val mac = Dev("mac")
    private val phone = Dev("phone")
    private val tablet = Dev("tablet")
    private val laptop = Dev("laptop")

    private suspend fun clusterOf(root: Dev, vararg members: Dev): List<ChainRecord> {
        val log = MembershipChainLog(InMemoryChainStore())
        log.append(root.signer, RecordKind.GENESIS, root.id, root.key, root.pin, "paired")
        members.forEach { log.append(root.signer, RecordKind.ADMIT, it.id, it.key, it.pin, "paired") }
        return log.recordsAfter(0L)
    }

    /** The database the Mac had: its own cluster, a second cluster's genesis and branch, and a record whose parent is unknown. */
    private suspend fun oldDatabase(): Pair<MembershipChainLog, InMemoryChainStore> {
        val store = InMemoryChainStore()
        clusterOf(mac, tablet).forEach { store.put(it) }
        clusterOf(phone, laptop).forEach { store.put(it) }
        val waiting = clusterOf(laptop, mac, phone).last()
        store.put(waiting)
        return MembershipChainLog(store, selfId = { "mac" }) to store
    }

    @Test
    fun theSecondClustersRecordsAreRemovedAndTheViewDoesNotChange() = runBlocking {
        val (log, store) = oldDatabase()
        val before = log.view()
        assertEquals(5, store.all().size)
        val removed = log.pruneOtherRoots()
        assertEquals(2, removed)
        assertEquals(3, store.all().size)
        assertEquals(1, store.all().count { it.kind == RecordKind.GENESIS })
        assertEquals(before.state, log.view().state)
        assertEquals(before.canonical, log.view().canonical)
    }

    @Test
    fun aRecordWaitingForItsParentStays() = runBlocking {
        val (log, store) = oldDatabase()
        val waiting = store.all().last()
        log.pruneOtherRoots()
        assertTrue(store.all().any { it.hash() == waiting.hash() })
    }

    @Test
    fun aSecondRunAndADatabaseWithOneRootChangeNothing() = runBlocking {
        val (log, store) = oldDatabase()
        log.pruneOtherRoots()
        assertEquals(0, log.pruneOtherRoots())
        val single = InMemoryChainStore().also { s -> clusterOf(mac, phone).forEach { s.put(it) } }
        assertEquals(0, MembershipChainLog(single, selfId = { "mac" }).pruneOtherRoots())
        assertEquals(2, single.all().size)
        assertEquals(3, store.all().size)
    }

    @Test
    fun anEmptyDatabaseHasNothingToPrune() = runBlocking {
        assertEquals(0, MembershipChainLog(InMemoryChainStore(), selfId = { "mac" }).pruneOtherRoots())
    }
}
