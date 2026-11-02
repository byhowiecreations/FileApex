package com.fileapex.membership

import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipChainLogTest {
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

    private fun log() = MembershipChainLog(InMemoryChainStore())

    private suspend fun MembershipChainLog.start() {
        append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
    }

    private suspend fun MembershipChainLog.copyTo(other: MembershipChainLog) { other.ingest(recordsAfter(0L)) }

    @Test
    fun recordsAppendedInOrderBuildTheChain() = runBlocking {
        val l = log()
        l.start()
        val view = l.view()
        assertEquals(2L, view.state.seq)
        assertEquals(listOf("mac", "phone"), view.state.activeIds().sorted())
    }

    @Test
    fun aRejectedRecordIsNotStored() = runBlocking {
        val l = log()
        l.start()
        val verdict = l.append(tablet.signer, RecordKind.ADMIT, laptop.id, laptop.key, laptop.pin, "paired")
        assertTrue(verdict is ChainVerdict.Rejected)
        assertEquals(2, l.recordsAfter(0L).size)
    }

    @Test
    fun recordsArrivingOutOfOrderGiveTheSameChain() = runBlocking {
        val a = log()
        a.start()
        a.append(mac.signer, RecordKind.ADMIT, tablet.id, tablet.key, tablet.pin, "paired")
        val b = log()
        b.ingest(a.recordsAfter(0L).reversed())
        assertEquals(a.view().state, b.view().state)
    }

    @Test
    fun badSignaturesAndRepeatsAreIgnored() = runBlocking {
        val a = log()
        a.start()
        val records = a.recordsAfter(0L)
        val b = log()
        val tampered = records[1].copy(subject = "other")
        assertEquals(1, b.ingest(listOf(records[0], tampered, records[0])))
        assertEquals(1L, b.view().state.seq)
    }

    @Test
    fun twoPartitionsThatActedAtOnceConvergeOnTheSameChainInEitherOrder() = runBlocking {
        val a = log()
        a.start()
        val b = log()
        a.copyTo(b)
        a.append(mac.signer, RecordKind.ADMIT, tablet.id, tablet.key, tablet.pin, "paired")
        b.append(phone.signer, RecordKind.ADMIT, laptop.id, laptop.key, laptop.pin, "paired")
        val x = log().also { it.ingest(a.recordsAfter(0L)); it.ingest(b.recordsAfter(0L)) }
        val y = log().also { it.ingest(b.recordsAfter(0L)); it.ingest(a.recordsAfter(0L)) }
        assertEquals(x.view().state.head, y.view().state.head)
        assertEquals(3L, x.view().state.seq)
    }

    @Test
    fun aRemovalBeatsALongerBranchThatDidNotRemove() = runBlocking {
        val a = log()
        a.start()
        val b = log()
        a.copyTo(b)
        a.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        b.append(mac.signer, RecordKind.ADMIT, tablet.id, tablet.key, tablet.pin, "paired")
        b.append(tablet.signer, RecordKind.ADMIT, laptop.id, laptop.key, laptop.pin, "paired")
        val merged = log().also { it.ingest(a.recordsAfter(0L)); it.ingest(b.recordsAfter(0L)) }
        assertEquals(listOf("mac"), merged.view().state.activeIds())
        assertEquals(3L, merged.view().state.seq)
    }

    @Test
    fun recordsAfterIncludesEveryStoredRecordAboveTheSequence() = runBlocking {
        val l = log()
        l.start()
        assertEquals(listOf(2L), l.recordsAfter(1L).map { it.seq })
    }

    @Test
    fun clearingTheLogLeavesNoChainAndAllowsANewGenesis() = runBlocking {
        val l = log()
        l.start()
        l.clear()
        assertEquals(0L, l.view().state.seq)
        assertTrue(l.recordsAfter(0L).isEmpty())
        assertTrue(l.append(tablet.signer, RecordKind.GENESIS, tablet.id, tablet.key, tablet.pin, "paired") is ChainVerdict.Accepted)
    }

    private suspend fun clusterOf(root: Dev, vararg members: Dev, self: String = root.id): MembershipChainLog {
        val l = MembershipChainLog(InMemoryChainStore()) { self }
        l.append(root.signer, RecordKind.GENESIS, root.id, root.key, root.pin, "paired")
        members.forEach { l.append(root.signer, RecordKind.ADMIT, it.id, it.key, it.pin, "paired") }
        return l
    }

    @Test
    fun anotherGenesisIsAnotherClusterAndIsNeverStoredOrServedOn() = runBlocking {
        val macs = clusterOf(mac, phone)
        val phones = clusterOf(phone, mac)
        macs.ingest(phones.recordsAfter(0L))
        phones.ingest(macs.recordsAfter(0L))
        assertEquals(listOf("mac", "phone"), macs.view().state.activeIds().sorted())
        assertEquals(2, macs.recordsAfter(0L).size)
        assertEquals("mac", macs.recordsAfter(0L).first().actor)
        assertEquals("phone", phones.recordsAfter(0L).first().actor)
        assertEquals(2, phones.recordsAfter(0L).size)
    }

    @Test
    fun aRemovalOnThisDevicesClusterIsNotOverruledByAnotherClustersGenesis() = runBlocking {
        val macs = clusterOf(mac, phone, tablet)
        macs.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        val phones = clusterOf(phone, mac, tablet)
        macs.ingest(phones.recordsAfter(0L))
        assertTrue(!macs.view().state.member("phone")!!.active)
        assertEquals(4L, macs.view().state.seq)
    }

    @Test
    fun aDatabaseThatAlreadyHoldsTwoGenesesFollowsTheOneThisDeviceWrote() = runBlocking {
        val both = InMemoryChainStore()
        clusterOf(mac, phone).recordsAfter(0L).forEach { both.put(it) }
        clusterOf(phone, mac, tablet).recordsAfter(0L).forEach { both.put(it) }
        assertEquals("mac", MembershipChainLog(both) { "mac" }.view().canonical.first().actor)
        assertEquals("phone", MembershipChainLog(both) { "phone" }.view().canonical.first().actor)
        assertEquals(2, MembershipChainLog(both) { "mac" }.recordsAfter(0L).size)
        assertEquals(3, MembershipChainLog(both) { "phone" }.recordsAfter(0L).size)
    }

    @Test
    fun aDeviceThatWroteNeitherGenesisAndHasTheSameStoreFollowsTheLowerHash() = runBlocking {
        val both = InMemoryChainStore()
        clusterOf(mac, phone).recordsAfter(0L).forEach { both.put(it) }
        clusterOf(phone, mac).recordsAfter(0L).forEach { both.put(it) }
        val lower = both.all().filter { it.kind == RecordKind.GENESIS }.minByOrNull { it.hash() }!!.actor
        assertEquals(lower, MembershipChainLog(both) { "tablet" }.view().canonical.first().actor)
    }
}
