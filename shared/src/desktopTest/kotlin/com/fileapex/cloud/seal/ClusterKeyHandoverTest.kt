package com.fileapex.cloud.seal

import com.fileapex.membership.ChainMember
import com.fileapex.membership.ChainState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.20.10 (one trust path): the cloud record key goes only to a member that is active in a chain that has seen the key's removal. */
class ClusterKeyHandoverTest {
    private val state = ChainState(10L, "h", mapOf("me" to ChainMember("k", "", true), "peer" to ChainMember("kp", "", true), "gone" to ChainMember("kg", "", false)))
    private fun key(seq: Long) = ClusterRecordKey.generate(2).withChainSeq(seq)

    @Test
    fun anActiveMemberGetsAKeyMadeAtOrBeforeThisDevicesChain() {
        assertTrue(ClusterKeyHandover.mayHand(state, "peer", key(10L)))
        assertTrue(ClusterKeyHandover.mayHand(state, "peer", key(0L)))
    }

    @Test
    fun aKeyNewerThanThisDevicesChainIsNotPassedOn() = assertFalse(ClusterKeyHandover.mayHand(state, "peer", key(11L)))

    @Test
    fun aRemovedOrUnknownDeviceGetsNothing() {
        assertFalse(ClusterKeyHandover.mayHand(state, "gone", key(5L)))
        assertFalse(ClusterKeyHandover.mayHand(state, "stranger", key(5L)))
    }

    @Test
    fun withoutAChainPairingStillHandsTheKeyOver() = assertTrue(ClusterKeyHandover.mayHand(ChainState.EMPTY, "peer", key(0L)))

    @Test
    fun theChainSeqSurvivesJsonAndAnOldKeyReadsAsZero() {
        assertEquals(7L, ClusterRecordKey.fromJson(key(7L).toJson())!!.chainSeq)
        val made = ClusterRecordKey.generate(1)
        val old = """{"epoch":1,"id":"${made.id}","keyBase64":"${java.util.Base64.getEncoder().encodeToString(made.bytes())}"}"""
        assertEquals(0L, ClusterRecordKey.fromJson(old)!!.chainSeq)
    }
}
