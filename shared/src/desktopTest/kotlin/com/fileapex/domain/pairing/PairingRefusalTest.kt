package com.fileapex.domain.pairing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingRefusalTest {
    @Test
    fun aHostWithoutTheVersion2RoutesIsOlder() {
        assertTrue(PairingRefusal.hostIsOlder(404, ""))
        assertTrue(PairingRefusal.hostIsOlder(403, "pairing_only"))
    }

    @Test
    fun anOrdinaryRefusalIsNotAnOlderHost() {
        assertFalse(PairingRefusal.hostIsOlder(403, "pairing_code_invalid"))
        assertFalse(PairingRefusal.hostIsOlder(403, "pairing_declined"))
        assertFalse(PairingRefusal.hostIsOlder(409, "pairing_busy"))
        assertFalse(PairingRefusal.hostIsOlder(500, "pairing_failed"))
    }
}
