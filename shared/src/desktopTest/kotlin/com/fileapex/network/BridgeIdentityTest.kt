package com.fileapex.network

import com.fileapex.network.routes.BridgeIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeIdentityTest {
    @Test fun aRequestNamingItsOwnCertificateDeviceOrNobodyPasses() {
        assertTrue(BridgeIdentity.matches(claimed = "", certificateDevice = "mac", path = "/api/v1/files/list"))
        assertTrue(BridgeIdentity.matches(claimed = "mac", certificateDevice = "mac", path = "/api/v1/files/list"))
    }

    @Test fun aRequestNamingAnotherDeviceIsRefusedOnEveryOrdinaryRoute() {
        assertFalse(BridgeIdentity.matches(claimed = "fx-new", certificateDevice = "fx-old", path = "/api/v1/files/list"))
        assertFalse(BridgeIdentity.matches(claimed = "fx-new", certificateDevice = "fx-old", path = "/api/v1/membership/chain"))
    }

    @Test fun onlyTheTwoIdentityChangeRoutesMayNameAnotherDevice() {
        assertTrue(BridgeIdentity.matches("fx-new", "fx-old", BridgeIdentity.CHANGE_PATH))
        assertTrue(BridgeIdentity.matches("fx-new", "fx-old", BridgeIdentity.STATUS_PATH + "?oldId=fx-old&nonce=n1"))
        assertFalse(BridgeIdentity.matches("fx-new", "fx-old", BridgeIdentity.CHANGE_PATH + "/extra"))
        assertFalse(BridgeIdentity.matches("fx-new", "fx-old", "/api/v2/identity/changex"))
    }

    @Test fun theRoutesStaySecuredForPairedPeers() {
        assertEquals(RouteAccess.Peer, RouteAuthPolicy.accessFor(BridgeIdentity.CHANGE_PATH))
        assertEquals(RouteAccess.Peer, RouteAuthPolicy.accessFor(BridgeIdentity.STATUS_PATH))
    }
}
