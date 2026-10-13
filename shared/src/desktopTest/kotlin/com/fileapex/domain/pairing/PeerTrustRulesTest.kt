package com.fileapex.domain.pairing

import com.fileapex.util.TimeUtils
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class PeerTrustRulesTest {
    @Test
    fun unstampedPeerIsCoveredByItsIntroducer() {
        assertTrue(PeerTrustRules.answersOnClusterClock(protocol = 2, version = 0L))
    }

    @Test
    fun stampedPeerMustBeOnTheClock() {
        assertTrue(PeerTrustRules.answersOnClusterClock(protocol = 2, version = TimeUtils.now() - 1_000L))
        assertFalse(PeerTrustRules.answersOnClusterClock(protocol = 2, version = TimeUtils.now() + 60L * 60L * 1000L))
    }

    @Test
    fun peerWithoutMembershipProtocolIsNotAccepted() {
        assertFalse(PeerTrustRules.answersOnClusterClock(protocol = 1, version = 0L))
        assertFalse(PeerTrustRules.answersOnClusterClock(protocol = 0, version = TimeUtils.now() - 1_000L))
    }
}
