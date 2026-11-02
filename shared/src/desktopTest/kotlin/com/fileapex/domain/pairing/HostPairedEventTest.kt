package com.fileapex.domain.pairing

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The host's QR screen closes on the pairing itself, not on a new row, so a device that was already on the roster closes it too. */
class HostPairedEventTest {
    @Test
    fun aJoinerTheHostAcceptedIsAnnouncedWhetherOrNotItWasAlreadyOnTheRoster() = runBlocking {
        val seen = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(2_000L) { LanPairingDiscovery.hostPaired.first() } }
        LanPairingDiscovery.onHostPairingAccepted(PairedJoiner("signature", "Signature"))
        assertEquals(PairedJoiner("signature", "Signature"), seen.await())
    }

    @Test
    fun aDeclinedOrExpiredPairingAnnouncesNoJoiner() = runBlocking {
        val seen = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(300L) { LanPairingDiscovery.hostPaired.first() } }
        LanPairingDiscovery.onHostPairingAccepted()
        assertNull(seen.await())
    }
}
