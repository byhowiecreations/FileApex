package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerRouteResolverTest {
    private val peer = PairedDeviceEntity(
        deviceId = "peer-1", deviceName = "Peer", lastKnownIp = "10.0.0.5", port = 8080, publicKeyHash = "",
        rootPath = "/", clientVersion = "0.19.0", clientVersionCode = 206
    )

    private class Probes : PeerRouteProbes {
        var network = true
        var lanAnswers = false
        var tailnetOn = false
        var tailnetOnline = false
        var tailnetAnswers = false
        var irohReady = false
        var irohAnswers = false
        val calls = mutableListOf<String>()

        override fun networkAvailable() = network
        override suspend fun lan(peer: PairedDeviceEntity): PeerRoute? {
            calls += "lan"
            return if (lanAnswers) PeerRoute(RouteKind.Lan, "10.0.0.5", 8080) else null
        }
        override fun tailnetEnabled() = tailnetOn
        override fun tailnetPeerOnline(peer: PairedDeviceEntity) = tailnetOnline
        override suspend fun tailnet(peer: PairedDeviceEntity): PeerRoute? {
            calls += "tailnet"
            return if (tailnetAnswers) PeerRoute(RouteKind.Tailnet, "100.64.0.9", 8080) else null
        }
        override fun irohUsable(peer: PairedDeviceEntity) = irohReady
        override suspend fun iroh(peer: PairedDeviceEntity): PeerRoute? {
            calls += "iroh"
            return if (irohAnswers) PeerRoute(RouteKind.Iroh, "127.0.0.1", 40000) else null
        }
    }

    private var clock = 1_000_000L
    private fun resolver(probes: Probes) = PeerRouteResolver(probes, now = { clock })

    private fun route(probes: Probes, use: RouteUse = RouteUse.Request, r: PeerRouteResolver = resolver(probes)) =
        runBlocking { r.routeFor(peer, use) }

    @Test
    fun lanWinsWhenItAnswers() {
        val p = Probes().apply { lanAnswers = true; tailnetOn = true; tailnetOnline = true; tailnetAnswers = true; irohReady = true; irohAnswers = true }
        assertEquals(RouteKind.Lan, route(p)?.kind)
        assertEquals(listOf("lan"), p.calls)
    }

    @Test
    fun tailnetIsNextWhenOnlineAndAnswering() {
        val p = Probes().apply { tailnetOn = true; tailnetOnline = true; tailnetAnswers = true; irohReady = true; irohAnswers = true }
        assertEquals(RouteKind.Tailnet, route(p)?.kind)
        assertEquals(listOf("lan", "tailnet"), p.calls)
    }

    @Test
    fun tailnetIsSkippedWhenTailscaleIsOffHere() {
        val p = Probes().apply { tailnetOn = false; tailnetOnline = true; tailnetAnswers = true; irohReady = true; irohAnswers = true }
        assertEquals(RouteKind.Iroh, route(p)?.kind)
        assertEquals(listOf("lan", "iroh"), p.calls)
    }

    @Test
    fun anOfflineTailnetPeerIsSkippedWithoutAProbe() {
        val p = Probes().apply { tailnetOn = true; tailnetOnline = false; tailnetAnswers = true; irohReady = true; irohAnswers = true }
        assertEquals(RouteKind.Iroh, route(p)?.kind)
        assertEquals(listOf("lan", "iroh"), p.calls)
    }

    @Test
    fun aTailnetAddressThatDoesNotAnswerFallsBackToIroh() {
        val p = Probes().apply { tailnetOn = true; tailnetOnline = true; tailnetAnswers = false; irohReady = true; irohAnswers = true }
        assertEquals(RouteKind.Iroh, route(p)?.kind)
        assertEquals(listOf("lan", "tailnet", "iroh"), p.calls)
    }

    @Test
    fun irohIsNotTriedWhenItIsOffOrThePeerHasNoAddress() {
        val p = Probes().apply { irohReady = false; irohAnswers = true }
        assertNull(route(p))
        assertEquals(listOf("lan"), p.calls)
    }

    @Test
    fun noRouteWhenEverythingFails() {
        val p = Probes().apply { tailnetOn = true; tailnetOnline = true; irohReady = true }
        assertNull(route(p))
        assertEquals(listOf("lan", "tailnet", "iroh"), p.calls)
    }

    @Test
    fun noNetworkMeansNoRouteAndNoProbe() {
        val p = Probes().apply { network = false; lanAnswers = true; irohReady = true; irohAnswers = true }
        assertNull(route(p))
        assertTrue(p.calls.isEmpty())
    }

    @Test
    fun addressExchangeNeverUsesIroh() {
        val p = Probes().apply { irohReady = true; irohAnswers = true }
        assertNull(route(p, RouteUse.AddressExchange))
        assertEquals(listOf("lan"), p.calls)
    }

    @Test
    fun everyOtherUseFollowsTheSameOrder() {
        for (use in listOf(RouteUse.Presence, RouteUse.Request, RouteUse.Bulk)) {
            val p = Probes().apply { tailnetOn = true; tailnetOnline = true; tailnetAnswers = true; irohReady = true; irohAnswers = true }
            assertEquals(use.name, RouteKind.Tailnet, route(p, use)?.kind)
            assertEquals(use.name, listOf("lan", "tailnet"), p.calls)
        }
    }

    @Test
    fun aReportedFailureSkipsThatRouteUntilItsTtlEnds() {
        val p = Probes().apply { lanAnswers = true; irohReady = true; irohAnswers = true }
        val r = resolver(p)
        assertEquals(RouteKind.Lan, route(p, r = r)?.kind)

        r.routeFailed(peer, RouteKind.Lan)
        p.calls.clear()
        assertEquals(RouteKind.Iroh, route(p, r = r)?.kind)
        assertEquals(listOf("iroh"), p.calls)

        clock += PeerRouteResolver.FAILED_ROUTE_TTL_MS - 1
        p.calls.clear()
        assertEquals(RouteKind.Iroh, route(p, r = r)?.kind)

        clock += 1
        p.calls.clear()
        assertEquals(RouteKind.Lan, route(p, r = r)?.kind)
        assertEquals(listOf("lan"), p.calls)
    }

    @Test
    fun aFailureOnOnePeerDoesNotAffectAnother() {
        val p = Probes().apply { lanAnswers = true }
        val r = resolver(p)
        val other = peer.copy(deviceId = "peer-2")
        r.routeFailed(peer, RouteKind.Lan)
        assertEquals(RouteKind.Lan, runBlocking { r.routeFor(other, RouteUse.Request) }?.kind)
        assertNull(runBlocking { r.routeFor(peer, RouteUse.Request) })
    }

    @Test
    fun aDialedHostBelongsToTheRouteThatProducedIt() {
        assertEquals(RouteKind.Tailnet, routeKindForHost("100.93.210.88"))
        assertEquals(RouteKind.Iroh, routeKindForHost("127.0.0.1"))
        assertEquals(RouteKind.Lan, routeKindForHost("192.168.1.20"))
        assertEquals(RouteKind.Lan, routeKindForHost("10.0.0.5"))
        assertEquals(RouteKind.Lan, routeKindForHost(" 172.16.16.218 "))
    }

    @Test
    fun aRouteChangeIsReportedOnceNotOnEveryCall() {
        val p = Probes().apply { lanAnswers = true; irohReady = true; irohAnswers = true }
        val seen = mutableListOf<String>()
        val r = PeerRouteResolver(p, { clock }) { _, route -> seen += route?.kind?.name ?: "none" }
        runBlocking {
            r.routeFor(peer, RouteUse.Request)
            r.routeFor(peer, RouteUse.Request)
            p.lanAnswers = false
            r.routeFor(peer, RouteUse.Request)
            r.routeFor(peer, RouteUse.Bulk)
            p.irohAnswers = false
            r.routeFor(peer, RouteUse.Request)
        }
        assertEquals(listOf("Lan", "Iroh", "none"), seen)
    }

    @Test
    fun anAddressExchangeLookupDoesNotCountAsARouteChange() {
        val p = Probes().apply { lanAnswers = true }
        val seen = mutableListOf<String>()
        val r = PeerRouteResolver(p, { clock }) { _, route -> seen += route?.kind?.name ?: "none" }
        runBlocking { r.routeFor(peer, RouteUse.AddressExchange) }
        assertTrue(seen.isEmpty())
    }
}
