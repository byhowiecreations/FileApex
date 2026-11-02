package com.fileapex.security.tls

import com.fileapex.data.db.PairedDeviceEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RouteTableJournalTest {
    private val lines = mutableListOf<String>()

    @Before
    fun record() {
        PeerTlsRoutes.update(emptyList())
        PeerTlsRoutes.journal = { lines += it }
    }

    @After
    fun reset() {
        PeerTlsRoutes.journal = null
        PeerTlsRoutes.update(emptyList())
    }

    private fun device(id: String, ip: String, pin: String = "a".repeat(64)) = PairedDeviceEntity(
        deviceId = id, deviceName = "n", lastKnownIp = ip, port = 8080, publicKeyHash = "", rootPath = "/", tlsPin = pin, tlsPort = 8081
    )

    @Test
    fun aChangeInThePeerTableIsWrittenWithLastOctetsOnly() {
        PeerTlsRoutes.update(listOf(device("fx-1", "192.168.50.11"), device("fx-2", "192.168.50.13")))
        assertEquals(1, lines.size)
        assertEquals("tls routes: 2 peers .11:8080>8081 .13:8080>8081", lines.single())
        assertTrue("no full address, id or pin", "172.16" !in lines.single() && "fx-" !in lines.single())
    }

    @Test
    fun theSameTableAgainIsNotWrittenTwice() {
        val roster = listOf(device("fx-1", "192.168.50.11"))
        PeerTlsRoutes.update(roster)
        PeerTlsRoutes.update(roster)
        assertEquals(1, lines.size)
    }

    @Test
    fun aPeerThatLosesItsPinLeavesTheTableAndTheLogSaysSo() {
        PeerTlsRoutes.update(listOf(device("fx-1", "192.168.50.11")))
        PeerTlsRoutes.update(listOf(device("fx-1", "192.168.50.11", pin = "")))
        assertEquals("tls routes: 0 peers", lines.last())
    }

    @Test
    fun aHeldRouteAndItsEndAreWritten() {
        val hold = PeerTlsRoutes.holdRoute("fx-gone", setOf("pin-a"), "10.0.0.5", 8080, 8081)
        assertEquals("tls routes: 1 peers .5:8080>8081", lines.last())
        hold.close()
        assertEquals("tls routes: 0 peers", lines.last())
    }
}
