package com.fileapex.membership

import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipChainTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val mac = Dev("mac")
    private val phone = Dev("phone")
    private val tablet = Dev("tablet")

    private fun genesis(by: Dev) = ChainRecords.sign(
        by.signer,
        ChainRecord(1, "", RecordKind.GENESIS, by.id, by.key, by.id, by.key, by.pin, "paired")
    )

    private fun next(state: ChainState, kind: RecordKind, by: Dev, subject: Dev, reason: String = "paired", pin: String = subject.pin) =
        ChainRecords.sign(by.signer, ChainRecord(state.seq + 1, state.head, kind, by.id, by.key, subject.id, subject.key, pin, reason))

    private fun accepted(v: ChainVerdict) = (v as ChainVerdict.Accepted).state

    private fun twoMembers(): ChainState {
        val s1 = accepted(MembershipChain.apply(ChainState.EMPTY, genesis(mac)))
        return accepted(MembershipChain.apply(s1, next(s1, RecordKind.ADMIT, mac, phone)))
    }

    @Test
    fun aGenesisCreatesTheChainWithOneActiveMember() {
        val s = accepted(MembershipChain.apply(ChainState.EMPTY, genesis(mac)))
        assertEquals(1L, s.seq)
        assertEquals(listOf("mac"), s.activeIds())
    }

    @Test
    fun anActiveMemberAdmitsAnotherAndTheHeadMoves() {
        val s = twoMembers()
        assertEquals(2L, s.seq)
        assertEquals(listOf("mac", "phone"), s.activeIds().sorted())
    }

    @Test
    fun aRecordWithTheWrongParentAGapOrARepeatedSequenceIsRejected() {
        val s = twoMembers()
        val good = next(s, RecordKind.ADMIT, mac, tablet)
        val wrongParent = ChainRecords.sign(mac.signer, good.copy(parentHash = "00".repeat(32), signature = ""))
        val gap = ChainRecords.sign(mac.signer, good.copy(seq = s.seq + 2, signature = ""))
        val repeat = ChainRecords.sign(mac.signer, good.copy(seq = s.seq, signature = ""))
        listOf(wrongParent, gap, repeat).forEach { assertTrue(MembershipChain.apply(s, it) is ChainVerdict.Rejected) }
        assertTrue(MembershipChain.apply(s, good) is ChainVerdict.Accepted)
    }

    @Test
    fun aReplayedOldRecordIsRejectedOnceTheChainHasMoved() {
        val s1 = accepted(MembershipChain.apply(ChainState.EMPTY, genesis(mac)))
        val admit = next(s1, RecordKind.ADMIT, mac, phone)
        val s2 = accepted(MembershipChain.apply(s1, admit))
        assertTrue(MembershipChain.apply(s2, admit) is ChainVerdict.Rejected)
    }

    @Test
    fun aTamperedOrForgedRecordIsRejected() {
        val s = twoMembers()
        val good = next(s, RecordKind.ADMIT, mac, tablet)
        assertTrue(MembershipChain.apply(s, good.copy(subject = "other")) is ChainVerdict.Rejected)
        assertTrue(MembershipChain.apply(s, good.copy(signature = "")) is ChainVerdict.Rejected)
        val byTablet = ChainRecords.sign(tablet.signer, good.copy(actor = "tablet", actorKey = tablet.key, signature = ""))
        val forged = good.copy(signature = byTablet.signature)
        assertTrue(MembershipChain.apply(s, forged) is ChainVerdict.Rejected)
    }

    @Test
    fun onlyAnActiveMemberWhoseKeyMatchesCanWriteRecords() {
        val s = twoMembers()
        assertTrue(MembershipChain.apply(s, next(s, RecordKind.ADMIT, tablet, tablet)) is ChainVerdict.Rejected)
        val imposter = Dev("mac")
        assertTrue(MembershipChain.apply(s, next(s, RecordKind.ADMIT, imposter, tablet)) is ChainVerdict.Rejected)
    }

    @Test
    fun aRemovedMemberCannotWriteAndTheLastActiveMemberCannotBeRemoved() {
        val s = twoMembers()
        val removed = accepted(MembershipChain.apply(s, next(s, RecordKind.REMOVE, mac, phone, reason = "user_removed")))
        assertEquals(listOf("mac"), removed.activeIds())
        assertTrue(MembershipChain.apply(removed, next(removed, RecordKind.ADMIT, phone, tablet)) is ChainVerdict.Rejected)
        assertTrue(MembershipChain.apply(removed, next(removed, RecordKind.REMOVE, mac, mac, reason = "user_removed")) is ChainVerdict.Rejected)
    }

    @Test
    fun aDeviceLeavesOnlyItself() {
        val s = twoMembers()
        assertTrue(MembershipChain.apply(s, next(s, RecordKind.LEAVE, mac, phone, reason = "left_cluster")) is ChainVerdict.Rejected)
        val left = accepted(MembershipChain.apply(s, next(s, RecordKind.LEAVE, phone, phone, reason = "left_cluster")))
        assertEquals(listOf("mac"), left.activeIds())
    }

    @Test
    fun aMemberAnnouncesItsOwnNewTlsPinAndNoOneElseCan() {
        val s = twoMembers()
        val newPin = "ab".repeat(32)
        val updated = accepted(MembershipChain.apply(s, next(s, RecordKind.TLS_PIN, phone, phone, pin = newPin)))
        assertEquals(newPin, updated.member("phone")!!.tlsPin)
        assertEquals(phone.key, updated.member("phone")!!.key)
        assertTrue(MembershipChain.apply(s, next(s, RecordKind.TLS_PIN, mac, phone, pin = newPin)) is ChainVerdict.Rejected)
        assertTrue(MembershipChain.apply(s, next(s, RecordKind.TLS_PIN, phone, phone, pin = "short")) is ChainVerdict.Rejected)
    }

    @Test
    fun twoRecordsWithOneParentResolveTheSameWayInEitherOrder() {
        val s = twoMembers()
        val admit = next(s, RecordKind.ADMIT, mac, tablet)
        val remove = next(s, RecordKind.REMOVE, phone, mac, reason = "user_removed")
        assertEquals(remove, MembershipChain.preferred(admit, remove))
        assertEquals(remove, MembershipChain.preferred(remove, admit))
        val other = next(s, RecordKind.ADMIT, phone, tablet)
        val lower = listOf(admit, other).minBy { it.hash() }
        assertEquals(lower, MembershipChain.preferred(admit, other))
        assertEquals(lower, MembershipChain.preferred(other, admit))
    }

    @Test
    fun theSignedBytesCannotBeReadTwoWays() {
        val a = ChainRecord(2, "p", RecordKind.ADMIT, "ab", "k", "c", "k2", "", "r")
        val b = ChainRecord(2, "p", RecordKind.ADMIT, "a", "k", "bc", "k2", "", "r")
        assertFalse(a.canonicalBytes().contentEquals(b.canonicalBytes()))
        assertNotEquals(a.hash(), b.hash())
    }

    private fun legacyAdmit(state: ChainState, by: Dev, subject: Dev) = ChainRecords.sign(
        by.signer,
        ChainRecord(state.seq + 1, state.head, RecordKind.ADMIT, by.id, by.key, subject.id, "legacy:${subject.id}", subject.pin, "paired")
    )

    @Test
    fun aKeylessLegacyAdmitCannotBringBackAMemberTheChainRemoved() {
        val s = twoMembers()
        val removed = accepted(MembershipChain.apply(s, next(s, RecordKind.REMOVE, mac, phone, MembershipReason.USER_REMOVED)))
        assertTrue(MembershipChain.apply(removed, legacyAdmit(removed, mac, phone)) is ChainVerdict.Rejected)
    }

    @Test
    fun aLegacyAdmitOfADeviceThatWasNeverInTheChainIsStillAccepted() {
        val s = twoMembers()
        assertTrue(MembershipChain.apply(s, legacyAdmit(s, mac, tablet)) is ChainVerdict.Accepted)
    }

    @Test
    fun aRemovedMemberIsAdmittedAgainWithANewRealKey() {
        val s = twoMembers()
        val removed = accepted(MembershipChain.apply(s, next(s, RecordKind.REMOVE, mac, phone, MembershipReason.USER_REMOVED)))
        val fresh = Dev("phone")
        val back = accepted(MembershipChain.apply(removed, next(removed, RecordKind.ADMIT, mac, fresh)))
        assertTrue(back.member("phone")!!.active)
        assertEquals(fresh.key, back.member("phone")!!.key)
    }

    private fun withKeylessTablet(): ChainState {
        val s = twoMembers()
        return accepted(MembershipChain.apply(s, legacyAdmit(s, mac, tablet)))
    }

    @Test
    fun anActiveRealKeyMemberBindsARealKeyToAnActiveKeylessMember() {
        val s = withKeylessTablet()
        val upgraded = accepted(MembershipChain.apply(s, next(s, RecordKind.ADMIT, mac, tablet)))
        assertEquals(tablet.key, upgraded.member("tablet")!!.key)
        assertTrue(upgraded.member("tablet")!!.active)
    }

    @Test
    fun aKeylessMemberCannotBeBoundToAnotherKeylessKeyAndARealKeyMemberCannotBeRebound() {
        val s = withKeylessTablet()
        assertTrue(MembershipChain.apply(s, legacyAdmit(s, mac, tablet)) is ChainVerdict.Rejected)
        val fresh = Dev("phone")
        assertTrue(MembershipChain.apply(s, next(s, RecordKind.ADMIT, mac, fresh)) is ChainVerdict.Rejected)
    }
}
