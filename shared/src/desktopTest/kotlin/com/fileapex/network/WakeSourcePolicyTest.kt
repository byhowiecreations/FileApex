package com.fileapex.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeSourcePolicyTest {
    private val local = listOf("172.16.16.60")

    @Test
    fun aPeerOnOurSubnetIsAccepted() {
        assertTrue(WakeSourcePolicy.accept("172.16.16.25", local))
    }

    @Test
    fun anotherSubnetOrACellularAddressIsDropped() {
        assertFalse(WakeSourcePolicy.accept("172.16.17.25", local))
        assertFalse(WakeSourcePolicy.accept("10.120.21.250", local))
        assertFalse(WakeSourcePolicy.accept("8.8.8.8", local))
    }

    @Test
    fun ourOwnBroadcastEchoIsDropped() {
        assertFalse(WakeSourcePolicy.accept("172.16.16.60", local))
    }

    @Test
    fun ipv6AndJunkAreDropped() {
        assertFalse(WakeSourcePolicy.accept("fe80::1%wlan0", local))
        assertFalse(WakeSourcePolicy.accept("2600:382:819b:dd45::1", local))
        assertFalse(WakeSourcePolicy.accept("", local))
        assertFalse(WakeSourcePolicy.accept("not an address", local))
    }

    @Test
    fun withNoLanAtAllNothingIsAccepted() {
        assertFalse(WakeSourcePolicy.accept("172.16.16.25", emptyList()))
    }

    @Test
    fun anyOfSeveralLocalSubnetsCounts() {
        assertTrue(WakeSourcePolicy.accept("192.168.1.9", listOf("172.16.16.60", "192.168.1.4")))
    }
}
