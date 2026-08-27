package com.fileapex.data.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterIdentityTest {

    @Test
    fun differentDevicesSharingAnIpAreNotTheSameNode() {
        assertFalse(
            RosterIdentity.samePhysicalDevice(
                incomingId = "magic8",
                incomingPublicKeyHash = "aaaa",
                otherId = "nimo",
                otherPublicKeyHash = "bbbb"
            )
        )
        assertTrue(
            RosterIdentity.shouldDetachStolenEndpoint(
                sameEndpoint = true,
                samePhysicalDevice = false
            )
        )
    }

    @Test
    fun matchingPublicKeyIsTheSameNode() {
        assertTrue(
            RosterIdentity.samePhysicalDevice(
                incomingId = "new-id",
                incomingPublicKeyHash = "deadbeef",
                otherId = "old-id",
                otherPublicKeyHash = "deadbeef"
            )
        )
        assertFalse(
            RosterIdentity.shouldDetachStolenEndpoint(
                sameEndpoint = true,
                samePhysicalDevice = true
            )
        )
    }

    @Test
    fun staleRosterIdIsTheSameNode() {
        assertTrue(
            RosterIdentity.samePhysicalDevice(
                incomingId = "live-id",
                incomingPublicKeyHash = "",
                otherId = "stale-id",
                otherPublicKeyHash = "",
                staleRosterId = "stale-id"
            )
        )
    }
}
