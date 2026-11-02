package com.fileapex.security.tls

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerStandingTest {
    @Test
    fun aPairedPinIsAPairedPeerWhateverElseIsTrue() {
        assertEquals(PeerStanding.Paired("mac"), PeerStanding.of(known = "mac", revoked = "honor", allowUnpinned = true))
        assertEquals(PeerStanding.Paired("mac"), PeerStanding.of(known = "mac", revoked = null, allowUnpinned = false))
    }

    @Test
    fun aRemovedDevicesPinIsToldItWasRemovedAndNothingElse() {
        assertEquals(PeerStanding.Revoked("honor"), PeerStanding.of(known = null, revoked = "honor", allowUnpinned = false))
    }

    @Test
    fun whileThisDeviceShowsItsPairingScreenARemovedDeviceMayPairAgain() {
        assertEquals(PeerStanding.PairingOnly, PeerStanding.of(known = null, revoked = "honor", allowUnpinned = true))
    }

    @Test
    fun aStrangerMayOnlyPairWhileThePairingScreenIsShown() {
        assertEquals(PeerStanding.PairingOnly, PeerStanding.of(known = null, revoked = null, allowUnpinned = true))
        assertEquals(PeerStanding.Rejected, PeerStanding.of(known = null, revoked = null, allowUnpinned = false))
    }
}
