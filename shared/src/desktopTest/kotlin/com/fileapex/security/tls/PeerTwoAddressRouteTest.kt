package com.fileapex.security.tls

import com.fileapex.data.db.PairedDeviceEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** A peer with two addresses (OMV answers on .11 and .12): whichever one a request uses, a pinned peer is never dialed in plain HTTP. */
class PeerTwoAddressRouteTest {
    private val pin = "a".repeat(64)

    @Before
    @After
    fun clear() {
        PeerTlsRoutes.update(emptyList())
    }

    private fun device(id: String, ip: String, pin: String = this.pin, tlsPin: String = pin) = PairedDeviceEntity(
        deviceId = id, deviceName = "n", lastKnownIp = ip, port = 8080, publicKeyHash = "", rootPath = "/", tlsPin = tlsPin, tlsPort = 8081
    )

    @Test
    fun theAddressThePeerLeftStaysRoutedOverTlsToTheSamePeer() {
        PeerTlsRoutes.update(listOf(device("omv", "192.168.50.11")))
        PeerTlsRoutes.update(listOf(device("omv", "192.168.50.12")))
        assertEquals("omv", PeerTlsRoutes.lookup("192.168.50.12", 8080)?.deviceId)
        val old = PeerTlsRoutes.lookup("192.168.50.11", 8080)
        assertEquals("omv", old?.deviceId)
        assertEquals(setOf(pin), old?.pins)
        assertEquals(8081, old?.tlsPort)
    }

    @Test
    fun anotherPeerNowAtThatAddressTakesItOver() {
        PeerTlsRoutes.update(listOf(device("omv", "192.168.50.11")))
        PeerTlsRoutes.update(listOf(device("omv", "192.168.50.12"), device("other", "192.168.50.11", pin = "b".repeat(64))))
        assertEquals("other", PeerTlsRoutes.lookup("192.168.50.11", 8080)?.deviceId)
    }

    @Test
    fun aRemovedOrUnpinnedPeerKeepsNoAddress() {
        PeerTlsRoutes.update(listOf(device("omv", "192.168.50.11")))
        PeerTlsRoutes.update(listOf(device("omv", "192.168.50.12")))
        PeerTlsRoutes.update(listOf(device("omv", "192.168.50.12", tlsPin = "")))
        assertNull(PeerTlsRoutes.lookup("192.168.50.11", 8080))
        assertNull(PeerTlsRoutes.lookup("192.168.50.12", 8080))
    }

    @Test
    fun aPeerGoneFromTheRosterKeepsNoAddress() {
        PeerTlsRoutes.update(listOf(device("omv", "192.168.50.11")))
        PeerTlsRoutes.update(emptyList())
        assertNull(PeerTlsRoutes.lookup("192.168.50.11", 8080))
    }

    @Test
    fun aRotatedPinReachesTheOlderAddressToo() {
        PeerTlsRoutes.update(listOf(device("omv", "192.168.50.11")))
        val rotated = "c".repeat(64)
        PeerTlsRoutes.update(listOf(device("omv", "192.168.50.12", pin = rotated)))
        assertEquals(setOf(rotated), PeerTlsRoutes.lookup("192.168.50.11", 8080)?.pins)
    }
}
