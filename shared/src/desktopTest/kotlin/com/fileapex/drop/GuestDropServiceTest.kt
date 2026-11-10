package com.fileapex.drop

import com.fileapex.friends.transfer.FriendsWire
import com.fileapex.iroh.IrohStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuestDropServiceTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var clock = 9_000_000L

    private class FakeEndpoint(override val id: String, private val relay: String?) : GuestDropEndpoint {
        val streams = Channel<IrohStream>(Channel.UNLIMITED)
        @Volatile var closed = false
        override val isAlive: Boolean get() = !closed
        override fun relayUrl() = relay
        override suspend fun accept(): IrohStream? = streams.receiveCatching().getOrNull()
        override suspend fun close() { closed = true; streams.close() }
    }

    private class FakeEngine(val relay: String? = "https://relay.example/", val dropRelayRefuses: Boolean = false) : GuestDropEngine {
        val started = mutableListOf<FakeEndpoint>()
        val keys = mutableListOf<ByteArray>()
        val relayUrls = mutableListOf<String?>()
        override suspend fun start(secretKey: ByteArray, useRelay: Boolean, relayUrl: String?): GuestDropEndpoint {
            keys += secretKey
            relayUrls += relayUrl
            return FakeEndpoint(started.size.toString(16).padStart(64, '0'), if (dropRelayRefuses && relayUrl != null) null else relay).also { started += it }
        }
    }

    private class FailingEngine : GuestDropEngine {
        override suspend fun start(secretKey: ByteArray, useRelay: Boolean, relayUrl: String?): GuestDropEndpoint = throw java.io.IOException("no network")
    }

    private class HangingEngine : GuestDropEngine {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun start(secretKey: ByteArray, useRelay: Boolean, relayUrl: String?): GuestDropEndpoint {
            entered.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
    }

    @After fun tearDown() { scope.cancel() }

    private fun service(
        engine: GuestDropEngine?,
        maxLive: Int = 3,
        drops: GuestDrops = GuestDrops(now = { clock }, maxLive = maxLive),
        allowed: () -> Boolean = { true },
        registrar: DropRelayRegistrar = DropRelayRegistrar.None
    ) = GuestDropService({ engine }, scope, drops, { clock }, allowed, registrar, "https://drop-relay.example")

    private class RecordingRegistrar(private val answers: MutableList<Boolean> = mutableListOf()) : DropRelayRegistrar {
        val calls = mutableListOf<Pair<List<String>, Long>>()
        fun willAnswer(vararg results: Boolean) = apply { answers += results.toList() }
        override suspend fun register(ids: List<String>, expiresAtMs: Long): Boolean {
            calls += ids to expiresAtMs
            return if (answers.isEmpty()) true else answers.removeAt(0)
        }
    }

    private fun file(size: Int = 100, name: String? = null) = (if (name == null) temp.newFile() else temp.newFile(name)).apply { writeBytes(ByteArray(size) { 1 }) }

    private fun ready(r: ShareResult) = (r as ShareResult.Ready)

    @Test fun sharingStartsTheEndpointAndGivesALinkThatNamesIt() = runBlocking {
        val engine = FakeEngine()
        val svc = service(engine)
        val r = ready(svc.share(file(name = "My File.bin"), GuestDrops.DEFAULT_TTL_MS))
        assertEquals(1, engine.started.size)
        val link = checkNotNull(GuestDropLink.parse(r.url))
        assertEquals(engine.started[0].id, link.endpointId)
        assertEquals("https://relay.example/", link.relayUrl)
        assertEquals("My File.bin", link.name)
        assertEquals(100L, link.sizeBytes)
        assertEquals(clock + GuestDrops.DEFAULT_TTL_MS, link.expiresAtMs)
        assertEquals(1, svc.active.value.size)
    }

    @Test fun theNameInTheLinkIsTheCleanedName() = runBlocking {
        val svc = service(FakeEngine())
        val f = temp.newFile("CON.txt").apply { writeBytes(ByteArray(3)) }
        assertEquals("file_CON.txt", checkNotNull(GuestDropLink.parse(ready(svc.share(f, 60_000L)).url)).name)
    }

    @Test fun eachRunOfTheEndpointGetsAFreshKey() = runBlocking {
        val engine = FakeEngine()
        val svc = service(engine)
        ready(svc.share(file(), 60_000L))
        svc.revokeAll()
        ready(svc.share(file(), 60_000L))
        assertEquals(2, engine.keys.size)
        assertFalse(engine.keys[0].contentEquals(engine.keys[1]))
        assertEquals(32, engine.keys[0].size)
    }

    @Test fun twoLinksShareOneEndpoint() = runBlocking {
        val engine = FakeEngine()
        val svc = service(engine)
        ready(svc.share(file(), 60_000L))
        ready(svc.share(file(), 60_000L))
        assertEquals(1, engine.started.size)
    }

    @Test fun theEndpointStopsWhenTheLastLinkIsRevoked() = runBlocking {
        val engine = FakeEngine()
        val svc = service(engine)
        val a = ready(svc.share(file(), 60_000L))
        val b = ready(svc.share(file(), 60_000L))
        svc.revoke(a.info.id)
        assertFalse(engine.started[0].closed)
        svc.revoke(b.info.id)
        assertTrue(engine.started[0].closed)
        assertTrue(svc.active.value.isEmpty())
    }

    @Test fun theEndpointStopsWhenTheLastLinkExpires() = runBlocking {
        val engine = FakeEngine()
        val svc = service(engine)
        ready(svc.share(file(), 60_000L))
        clock += 61_000L
        svc.refresh()
        assertTrue(engine.started[0].closed)
        assertTrue(svc.active.value.isEmpty())
    }

    @Test fun theEndpointStopsRightAfterTheOnlyDownloadFinishes() = runBlocking {
        withTimeout(30_000L) {
            val engine = FakeEngine()
            val svc = service(engine)
            val r = ready(svc.share(file(500), 600_000L))
            val (client, host) = com.fileapex.iroh.memoryLinkPair()
            val visitor = BoundedStream(client.open("peer-b"))
            engine.started[0].streams.send(BoundedStream(host.accept()!!.stream))
            FriendsWire.writeFrame(visitor, GuestDropWire.encodeRequest(r.info.id, checkNotNull(GuestDropLink.parse(r.url)).secret))
            assertEquals("ok", FriendsWire.readFrame(visitor, 5_000L).decodeToString().substringBefore("\n"))
            var left = 500
            while (left > 0) left -= visitor.read(left)!!.size
            FriendsWire.writeRaw(visitor, GuestDropWire.RECEIPT)
            while (!engine.started[0].closed) delay(20)
            assertTrue(svc.active.value.isEmpty())
        }
    }

    @Test fun sharingFailsCleanlyWithoutIroh() = runBlocking {
        assertEquals(ShareResult.Failed(ShareFailure.NotAvailable), service(null).share(file(), 60_000L))
    }

    @Test fun sharingFailsWhenTheEndpointHasNoRelayAndStopsIt() = runBlocking {
        val engine = FakeEngine(relay = null)
        val svc = service(engine)
        assertEquals(ShareResult.Failed(ShareFailure.NoRelay), svc.share(file(), 60_000L))
        assertTrue(engine.started[0].closed)
        assertTrue(svc.active.value.isEmpty())
    }

    @Test fun aMissingOrEmptyPathIsNotShared() = runBlocking {
        val svc = service(FakeEngine())
        assertEquals(ShareResult.Failed(ShareFailure.NotAFile), svc.share(File(temp.root, "nope"), 60_000L))
        assertEquals(ShareResult.Failed(ShareFailure.NotAFile), svc.share(temp.root, 60_000L))
    }

    @Test fun theCapIsReportedAndDoesNotStartAnEndpoint() = runBlocking {
        val engine = FakeEngine()
        val svc = service(engine, maxLive = 1)
        ready(svc.share(file(), 60_000L))
        assertEquals(ShareResult.Failed(ShareFailure.TooMany), svc.share(file(), 60_000L))
        assertEquals(1, engine.started.size)
    }

    @Test fun anEndpointThatCannotStartIsReportedAndLeavesNothingBehind() = runBlocking {
        val drops = GuestDrops(now = { clock }, maxLive = 1)
        val svc = service(FailingEngine(), drops = drops)
        assertEquals(ShareResult.Failed(ShareFailure.CouldNotStart), svc.share(file(), 60_000L))
        assertTrue(drops.list().isEmpty())
        assertTrue(svc.active.value.isEmpty())
        assertEquals(ShareResult.Failed(ShareFailure.CouldNotStart), svc.share(file(), 60_000L))
    }

    @Test fun cancellingTheShareWhileTheEndpointStartsLeavesNoInvisibleLink() = runBlocking {
        withTimeout(30_000L) {
            val drops = GuestDrops(now = { clock }, maxLive = 1)
            val engine = HangingEngine()
            val svc = service(engine, drops = drops)
            val job = scope.launch { svc.share(file(), 60_000L) }
            engine.entered.await()
            job.cancelAndJoin()
            assertTrue("the slot must be free again", drops.list().isEmpty())
            assertTrue(svc.active.value.isEmpty())
            assertNotNull(drops.create("/p", "p", 1L, 60_000L))
        }
    }

    @Test fun anOpenLinkShowsDownloadingWhileSomeoneIsPullingAndNotAfter() = runBlocking {
        withTimeout(30_000L) {
            val engine = FakeEngine()
            val svc = service(engine)
            val r = ready(svc.share(file(4_000_000), 600_000L))
            val (client, host) = com.fileapex.iroh.memoryLinkPair()
            val visitor = BoundedStream(client.open("peer-b"))
            engine.started[0].streams.send(BoundedStream(host.accept()!!.stream))
            FriendsWire.writeFrame(visitor, GuestDropWire.encodeRequest(r.info.id, checkNotNull(GuestDropLink.parse(r.url)).secret))
            FriendsWire.readFrame(visitor, 5_000L)
            while (svc.active.value.none { it.downloading }) delay(20)
            assertTrue(svc.active.value.single().downloading)
            visitor.abort()
            while (svc.active.value.any { it.downloading }) delay(20)
            assertEquals(1, svc.active.value.size)
        }
    }

    @Test fun theLinkRecordsWhenTheFileWasLastChanged() = runBlocking {
        val drops = GuestDrops(now = { clock }, maxLive = 3)
        val svc = service(FakeEngine(), drops = drops)
        val f = file()
        f.setLastModified(1_700_000_000_000L)
        val r = ready(svc.share(f, 60_000L))
        val secret = checkNotNull(GuestDropLink.parse(r.url)).secret
        f.setLastModified(1_700_000_005_000L)
        val begin = drops.begin(r.info.id, secret) as DropBegin.Granted
        assertEquals(1_700_000_000_000L, begin.lease.lastModifiedMs)
    }

    private class SilentStream : IrohStream {
        val aborts = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun read(max: Int): ByteArray? { kotlinx.coroutines.awaitCancellation() }
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun finish() = Unit
        override fun abort() { aborts.incrementAndGet() }
    }

    @Test fun theEndpointStaysUpWhileASpentDownloadStillWaitsForItsReceipt() = runBlocking {
        withTimeout(30_000L) {
            val engine = FakeEngine()
            val svc = service(engine)
            val r = ready(svc.share(file(500), 600_000L))
            val (client, host) = com.fileapex.iroh.memoryLinkPair()
            val visitor = BoundedStream(client.open("peer-b"))
            engine.started[0].streams.send(BoundedStream(host.accept()!!.stream))
            FriendsWire.writeFrame(visitor, GuestDropWire.encodeRequest(r.info.id, checkNotNull(GuestDropLink.parse(r.url)).secret))
            FriendsWire.readFrame(visitor, 5_000L)
            var left = 500
            while (left > 0) left -= visitor.read(left)!!.size
            while (svc.active.value.isNotEmpty()) delay(20)
            svc.refresh()
            delay(300)
            assertFalse("the endpoint must not close before the visitor's receipt", engine.started[0].closed)
            FriendsWire.writeRaw(visitor, GuestDropWire.RECEIPT)
            while (!engine.started[0].closed) delay(20)
        }
    }

    @Test fun visitorsBeyondTheServeLimitAreTurnedAwayAtOnce() = runBlocking {
        withTimeout(30_000L) {
            val engine = FakeEngine()
            val svc = service(engine)
            ready(svc.share(file(), 600_000L))
            val streams = List(GuestDropService.MAX_SERVES + 3) { SilentStream() }
            streams.forEach { engine.started[0].streams.send(it) }
            while (streams.drop(GuestDropService.MAX_SERVES).any { it.aborts.get() == 0 }) delay(20)
            assertEquals(0, streams.take(GuestDropService.MAX_SERVES).sumOf { it.aborts.get() })
        }
    }

    private class RecordingFile(path: String, val threads: MutableSet<String>) : File(path) {
        override fun isFile(): Boolean { threads += Thread.currentThread().name; return super.isFile() }
        override fun length(): Long { threads += Thread.currentThread().name; return super.length() }
        override fun lastModified(): Long { threads += Thread.currentThread().name; return super.lastModified() }
    }

    @Test fun sharingReadsTheFileOffTheCallersThread() = runBlocking {
        withTimeout(30_000L) {
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller-thread") }
            try {
                val threads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                val svc = service(FakeEngine())
                val f = RecordingFile(file().path, threads)
                kotlinx.coroutines.withContext(executor.asCoroutineDispatcher()) { ready(svc.share(f, 60_000L)) }
                assertTrue(threads.isNotEmpty())
                assertTrue("file work ran on $threads", threads.none { it.startsWith("caller-thread") })
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test fun withoutTheOwnersAcceptanceNoLinkIsMadeAndNothingStarts() = runBlocking {
        val engine = FakeEngine()
        val drops = GuestDrops(now = { clock }, maxLive = 1)
        var accepted = false
        val svc = service(engine, drops = drops, allowed = { accepted })
        assertEquals(ShareResult.Failed(ShareFailure.NotAccepted), svc.share(file(), 60_000L))
        assertTrue(engine.started.isEmpty())
        assertTrue(drops.list().isEmpty())
        accepted = true
        ready(svc.share(file(), 60_000L))
        assertEquals(1, engine.started.size)
    }

    @Test fun aRegisteredLinkUsesTheDropRelayAndListsTheSenderAndTheGuestIds() = runBlocking {
        val engine = FakeEngine()
        val registrar = RecordingRegistrar()
        val r = ready(service(engine, registrar = registrar).share(file(), 3_600_000L))
        val link = checkNotNull(GuestDropLink.parse(r.url))
        assertEquals(listOf<String?>("https://drop-relay.example"), engine.relayUrls)
        assertEquals(GuestDropKeys.COUNT, link.guestKeys)
        assertTrue(r.url.contains("#v2."))
        assertEquals(1, registrar.calls.size)
        val (ids, expires) = registrar.calls[0]
        assertEquals(GuestDropKeys.endpointId(engine.keys[0]), ids[0])
        assertEquals(GuestDropKeys.guestIds(link.secret), ids.drop(1))
        assertEquals(clock + 3_600_000L, expires)
    }

    @Test fun whenTheRegistryCannotBeReachedTheLinkFallsBackToTheOrdinaryRelay() = runBlocking {
        val engine = FakeEngine()
        val r = ready(service(engine, registrar = RecordingRegistrar().willAnswer(false)).share(file(), 60_000L))
        assertEquals(listOf<String?>(null), engine.relayUrls)
        assertEquals(0, checkNotNull(GuestDropLink.parse(r.url)).guestKeys)
        assertTrue(r.url.contains("#v1."))
    }

    @Test fun withoutARegistrarLinksUseTheOrdinaryRelay() = runBlocking {
        val engine = FakeEngine()
        val r = ready(service(engine).share(file(), 60_000L))
        assertEquals(listOf<String?>(null), engine.relayUrls)
        assertTrue(r.url.contains("#v1."))
    }

    @Test fun aSecondLinkListsItsOwnGuestIdsAndTheSameSenderId() = runBlocking {
        val engine = FakeEngine()
        val registrar = RecordingRegistrar()
        val svc = service(engine, registrar = registrar)
        ready(svc.share(file(), 60_000L))
        ready(svc.share(file(), 7_200_000L))
        assertEquals(1, engine.started.size)
        assertEquals(2, registrar.calls.size)
        assertEquals(registrar.calls[0].first[0], registrar.calls[1].first[0])
        assertTrue(registrar.calls[0].first.drop(1).intersect(registrar.calls[1].first.drop(1).toSet()).isEmpty())
        assertEquals(clock + 7_200_000L, registrar.calls[1].second)
    }

    @Test fun aSecondLinkOnTheDropRelayWhoseRegistrationFailsIsRefusedAndNotKept() = runBlocking {
        val engine = FakeEngine()
        val svc = service(engine, registrar = RecordingRegistrar().willAnswer(true, false))
        ready(svc.share(file(), 60_000L))
        val second = svc.share(file(), 60_000L)
        assertEquals(ShareResult.Failed(ShareFailure.CouldNotStart), second)
        assertEquals(1, svc.active.value.size)
    }

    @Test fun aDropRelayThatDoesNotTakeTheEndpointFallsBackToTheOrdinaryRelay() = runBlocking {
        val engine = FakeEngine(dropRelayRefuses = true)
        val r = ready(service(engine, registrar = RecordingRegistrar()).share(file(), 60_000L))
        assertEquals(listOf<String?>("https://drop-relay.example", null), engine.relayUrls)
        assertTrue(engine.started[0].closed)
        assertFalse(engine.started[1].closed)
        assertTrue(r.url.contains("#v1."))
    }
}
