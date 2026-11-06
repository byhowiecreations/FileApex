package com.fileapex.iroh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohRelayStarterTest {
    private class FakeHandle(override val online: Boolean) : IrohLinkHandle {
        var closed = false
        override val id = "id"
        override fun address() = "id||"
        override fun addPeer(address: String) = "id"
        override fun isConnected(peerId: String) = false
        override suspend fun connect(peerId: String) = Unit
        override fun pathKind(peerId: String): String? = null
        override fun onAddressChanged(onChange: () -> Unit) = Unit
        override suspend fun accept(): IrohInbound? = null
        override suspend fun open(peerId: String): IrohStream = error("not used")
        override suspend fun close() { closed = true }
    }

    /** Hands out handles in order; [customOnline] decides whether a start with a relay comes online. */
    private class FakeEngine(private val customOnline: Boolean) : IrohEngine {
        val relays = mutableListOf<IrohRelayConfig?>()
        val handles = mutableListOf<FakeHandle>()
        override suspend fun start(secretKey: ByteArray, relay: IrohRelayConfig?): IrohLinkHandle {
            relays += relay
            return FakeHandle(online = relay == null || customOnline).also { handles += it }
        }
    }

    private var now = 1_000_000L
    private val fallback = IrohRelayFallback { now }
    private val starter = IrohRelayStarter(fallback)
    private val custom = IrohRelayConfig("https://relay.example.com", "token")
    private val secret = ByteArray(32)

    @Test
    fun noCustomRelayStartsOnN0AndLeavesTheFallbackAlone() = runBlocking {
        val engine = FakeEngine(customOnline = true)
        val started = starter.start(engine, secret, null)
        assertEquals(listOf<IrohRelayConfig?>(null), engine.relays)
        assertFalse(started.usedCustom)
        assertFalse(fallback.isFallback)
    }

    @Test
    fun aCustomRelayThatComesOnlineIsKept() = runBlocking {
        val engine = FakeEngine(customOnline = true)
        val started = starter.start(engine, secret, custom)
        assertEquals(listOf<IrohRelayConfig?>(custom), engine.relays)
        assertTrue(started.usedCustom)
        assertSame(engine.handles[0], started.link)
        assertFalse(fallback.isFallback)
    }

    @Test
    fun aCustomRelayThatNeverComesOnlineIsClosedAndN0TakesOver() = runBlocking {
        val engine = FakeEngine(customOnline = false)
        val started = starter.start(engine, secret, custom)
        assertEquals(listOf<IrohRelayConfig?>(custom, null), engine.relays)
        assertTrue("the dead endpoint is shut down", engine.handles[0].closed)
        assertFalse(engine.handles[1].closed)
        assertFalse(started.usedCustom)
        assertSame(engine.handles[1], started.link)
        assertTrue(fallback.isFallback)
    }

    @Test
    fun whileTheFallbackWaitsTheCustomRelayIsNotTriedAgain() = runBlocking {
        fallback.customFailed()
        val engine = FakeEngine(customOnline = false)
        val started = starter.start(engine, secret, custom)
        assertEquals(listOf<IrohRelayConfig?>(null), engine.relays)
        assertFalse(started.usedCustom)
    }

    @Test
    fun onceTheWaitIsOverTheCustomRelayIsTriedAndAWorkingOneEndsTheFallback() = runBlocking {
        fallback.customFailed()
        now += 5 * 60_000L
        val engine = FakeEngine(customOnline = true)
        val started = starter.start(engine, secret, custom)
        assertEquals(listOf<IrohRelayConfig?>(custom), engine.relays)
        assertTrue(started.usedCustom)
        assertFalse(fallback.isFallback)
    }

    @Test
    fun aRetryThatFailsAgainGoesBackToN0AndWaitsLonger() = runBlocking {
        fallback.customFailed()
        now += 5 * 60_000L
        val engine = FakeEngine(customOnline = false)
        starter.start(engine, secret, custom)
        assertEquals(listOf<IrohRelayConfig?>(custom, null), engine.relays)
        now += 5 * 60_000L
        assertFalse("second wait is ten minutes", fallback.retryDue())
        now += 5 * 60_000L
        assertTrue(fallback.retryDue())
        assertNull(engine.relays.last())
    }

    @Test
    fun theStarterExposesTheFallbackItDecidesWith() {
        assertSame(fallback, starter.fallback)
    }
}
