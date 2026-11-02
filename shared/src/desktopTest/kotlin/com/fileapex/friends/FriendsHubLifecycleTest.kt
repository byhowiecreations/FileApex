package com.fileapex.friends

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.settings.AppSettings
import com.fileapex.data.settings.BaseAppSettings
import com.fileapex.friends.data.FriendLinkEntity
import com.fileapex.friends.data.FriendsDao
import com.fileapex.friends.transfer.FriendOffer
import com.fileapex.friends.transfer.FriendsTransferBoard
import com.fileapex.friends.transfer.FriendsWire
import com.fileapex.iroh.IrohStream
import com.fileapex.notice.MapKvStore
import java.io.ByteArrayInputStream
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FriendsHubLifecycleTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var database: FileApexDatabase
    private val replaced = linkedMapOf<Field, Any?>()
    private val settings = BaseAppSettings(MapKvStore())
    private val key = UUID.randomUUID().toString().replace("-", "") + "ab".repeat(16)
    private val endpoints = mutableListOf<ListenerEndpoint>()
    private val extraKeys = mutableListOf<String>()

    private fun replace(name: String, value: Any?) {
        val field = FriendsHub::class.java.getDeclaredField(name).apply { isAccessible = true }
        if (field !in replaced) replaced[field] = field.get(FriendsHub)
        field.set(FriendsHub, value)
    }

    @Before fun setUp() {
        database = Room.databaseBuilder<FileApexDatabase>(temp.root.resolve("friends.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        replace("dao", database.friendsDao())
        replace("settingsProvider", { settings as AppSettings })
        replace("downloadsProvider", { temp.root.resolve("inbox").absolutePath })
        replace("engineProvider", { null as FriendsEngine? })
        replace("endpoint", null)
    }

    @After fun tearDown() {
        runBlocking { (extraKeys + key).forEach { FriendsHub.remove(it) }; endpoints.forEach { it.close() } }
        val board = FriendsHub::class.java.getDeclaredField("board").apply { isAccessible = true }.get(FriendsHub) as FriendsTransferBoard
        board.transfers.value.filter { it.friendKey == key }.forEach { board.drop(it.id) }
        replaced.entries.reversed().forEach { (field, value) -> field.set(FriendsHub, value) }
        if (::database.isInitialized) database.close()
    }

    private suspend fun seed() {
        database.friendsDao().putLink(FriendLinkEntity(key, "Alex", "Friends", 1, "Alex"))
    }

    private class OfferedStream(offer: FriendOffer) : IrohStream {
        private val payload = FriendsWire.encodeOffer(offer)
        private val input = ByteArrayInputStream(byteArrayOf(2) + ByteBuffer.allocate(4).putInt(payload.size).array() + payload)
        var aborted = false
        var reads = 0
        override suspend fun read(max: Int): ByteArray? {
            reads++
            return if (aborted || input.available() == 0) null else input.readNBytes(minOf(max, input.available()))
        }
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun finish() = Unit
        override fun abort() { aborted = true }
    }

    private suspend fun handle(stream: IrohStream) {
        val method = FriendsHub::class.java.declaredMethods.single { it.name == "handle" }.apply { isAccessible = true }
        suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            try { method.invoke(FriendsHub, FriendsInbound(key, stream), continuation) }
            catch (e: InvocationTargetException) { throw e.targetException }
        }
    }

    private class KindStream(private val kind: Int) : IrohStream {
        private var sent = false
        var aborted = false
        override suspend fun read(max: Int): ByteArray? = if (aborted || sent) null else { sent = true; byteArrayOf(kind.toByte()) }
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun finish() = Unit
        override fun abort() { aborted = true }
    }

    private class GoodbyeStream : IrohStream {
        val written = CompletableDeferred<List<Byte>>()
        private val bytes = mutableListOf<Byte>()
        override suspend fun read(max: Int): ByteArray? = null
        override suspend fun write(bytes: ByteArray) { this.bytes += bytes.toList() }
        override suspend fun finish() { written.complete(bytes.toList()) }
        override fun abort() { written.complete(bytes.toList()) }
    }

    private fun startFakeEndpoint(dial: suspend (String) -> IrohStream = { throw java.io.IOException("No outbound stream") }): ListenerEndpoint {
        settings.setFriendsSecretKeyBase64(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        val endpoint = ListenerEndpoint().also { endpoints += it }
        endpoint.dial = dial
        replace("engineProvider", { object : FriendsEngine {
            override suspend fun start(secretKey: ByteArray, hideIp: Boolean, admit: suspend (String) -> Boolean) = endpoint
        } })
        return endpoint
    }

    private fun clearRemovedYou() {
        val field = FriendsHub::class.java.getDeclaredField("removalNotice").apply { isAccessible = true }.get(FriendsHub)
        val flow = field.javaClass.getDeclaredField("removedYouInternal").apply { isAccessible = true }.get(field) as MutableStateFlow<*>
        @Suppress("UNCHECKED_CAST") (flow as MutableStateFlow<Set<String>>).value = emptySet()
    }

    @Test fun aGoodbyeFromAFriendMarksItRemovedYouAndKeepsTheFriend() = runBlocking {
        seed()
        try {
            handle(KindStream(4))
            assertTrue(key in FriendsHub.removedYou.value)
            assertNotNull(database.friendsDao().link(key))
        } finally { clearRemovedYou() }
    }

    @Test fun aFriendThatClosesOurConnectionAsNotAFriendIsMarkedRemovedYou() = runBlocking {
        val endpoint = startFakeEndpoint()
        seed()
        try {
            FriendsHub.connect(key)
            assertFalse(key in FriendsHub.removedYou.value)
            endpoint.refused = true
            FriendsHub.connect(key)
            assertTrue(key in FriendsHub.removedYou.value)
        } finally { clearRemovedYou() }
    }

    @Test fun noConnectionIsTriedAgainOnceAFriendRemovedUs() = runBlocking {
        val endpoint = startFakeEndpoint()
        seed()
        endpoint.refused = true
        try {
            FriendsHub.connect(key)
            val before = endpoint.connects
            FriendsHub.connect(key)
            FriendsHub.connect(key)
            assertEquals(before, endpoint.connects)
        } finally { clearRemovedYou() }
    }

    @Test fun aGoodbyeFromAStrangerIsDroppedAndMarksNothing() = runBlocking {
        val stream = KindStream(4)
        handle(stream)
        assertTrue(stream.aborted)
        assertFalse(key in FriendsHub.removedYou.value)
    }

    @Test fun removingAFriendSendsOneGoodbyeByte() = runBlocking {
        val goodbye = GoodbyeStream()
        startFakeEndpoint { peer -> assertEquals(key, peer); goodbye }
        seed()
        FriendsHub.connect(key)
        FriendsHub.remove(key)
        assertEquals(listOf<Byte>(4), withTimeout(5_000L) { goodbye.written.await() })
        assertNull(database.friendsDao().link(key))
    }

    @Test fun removingAFriendDoesNotWaitForAnUnreachableFriend() = runBlocking {
        startFakeEndpoint { awaitCancellation() }
        seed()
        FriendsHub.connect(key)
        withTimeout(5_000L) { FriendsHub.remove(key) }
        assertNull(database.friendsDao().link(key))
    }

    @Test fun removingAFriendWhoRemovedUsSendsNoGoodbyeAndClearsTheMark() = runBlocking {
        val dialed = MutableStateFlow(false)
        startFakeEndpoint { dialed.value = true; GoodbyeStream() }
        seed()
        FriendsHub.connect(key)
        handle(KindStream(4))
        assertTrue(key in FriendsHub.removedYou.value)
        FriendsHub.remove(key)
        delay(300)
        assertFalse(dialed.value)
        assertFalse(key in FriendsHub.removedYou.value)
    }

    @Test fun removingAPinnedFriendClearsItsSavedPin() = runBlocking {
        seed()
        FriendsHub.setPinned(key, true)
        try {
            FriendsHub.remove(key)
            assertFalse(FriendsHub.pinned.value.contains(key))
            assertFalse(settings.friendsPinnedKeys().split(',').contains(key))
        } finally {
            val field = FriendsHub::class.java.getDeclaredField("pinnedInternal").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val pins = field.get(FriendsHub) as MutableStateFlow<Set<String>>
            pins.update { it - key }
        }
    }

    @Test fun removalStopsARealHandlerWaitingForAcceptanceAndDropsItsPrompt() = runBlocking {
        seed()
        val stream = OfferedStream(FriendOffer("ab".repeat(16), 4, "waiting.bin"))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { FriendsHub.pendingOffers.first { list -> list.any { it.friendKey == key } } }
        val receiving = async { handle(stream) }
        withTimeout(5_000L) { pending.await() }
        assertTrue(FriendsHub.hasActiveWork)
        FriendsHub.remove(key)
        assertTrue(receiving.isCancelled)
        assertTrue(stream.aborted)
        assertTrue(FriendsHub.pendingOffers.value.none { it.friendKey == key })
        assertNull(database.friendsDao().link(key))
        assertFalse(FriendsHub.hasActiveWork)
    }

    @Test fun aRemovedFriendsExistingConnectionCannotOfferAnotherFile() = runBlocking {
        seed()
        FriendsHub.remove(key)
        val stream = OfferedStream(FriendOffer("cd".repeat(16), 4, "stale.bin"))
        handle(stream)
        assertTrue(stream.aborted)
        assertEquals(0, stream.reads)
        assertTrue(FriendsHub.pendingOffers.value.none { it.friendKey == key })
    }

    @Test fun anUnavailableBuildDoesNotQueueFilesForStoredFriends() = runBlocking {
        seed()
        val source = temp.newFile("source.bin").apply { writeText("keep") }
        assertFalse(FriendsHub.enqueueSend(key, listOf(source)))
        assertTrue(FriendsHub.transfers.value.none { it.friendKey == key })
        assertEquals("keep", source.readText())
    }

    @Test fun queueingFilesDoesNoFileSystemWorkOnTheCallingThread() = runBlocking {
        settings.setFriendsSecretKeyBase64(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        val endpoint = ListenerEndpoint().also { endpoints += it }
        endpoint.dial = { awaitCancellation() }
        replace("engineProvider", { object : FriendsEngine {
            override suspend fun start(secretKey: ByteArray, hideIp: Boolean, admit: suspend (String) -> Boolean) = endpoint
        } })
        seed()
        val real = temp.newFile("probe.bin").apply { writeText("probe") }
        val touchedBy = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val probe = object : java.io.File(real.path) {
            override fun isFile() = super.isFile().also { touchedBy += Thread.currentThread().name }
            override fun canRead() = super.canRead().also { touchedBy += Thread.currentThread().name }
            override fun length() = super.length().also { touchedBy += Thread.currentThread().name }
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "ui-like") }
        try {
            val queued = withContext(executor.asCoroutineDispatcher()) { FriendsHub.enqueueSend(key, listOf(probe)) }
            assertTrue(queued)
            assertTrue(touchedBy.isNotEmpty())
            assertFalse("File checks ran on ${touchedBy}", "ui-like" in touchedBy)
        } finally { executor.shutdownNow() }
    }

    @Test fun aSendSurvivesTheScreenThatStartedItClosingAtOnce() = runBlocking {
        settings.setFriendsSecretKeyBase64(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        val endpoint = ListenerEndpoint().also { endpoints += it }
        endpoint.dial = { awaitCancellation() }
        replace("engineProvider", { object : FriendsEngine {
            override suspend fun start(secretKey: ByteArray, hideIp: Boolean, admit: suspend (String) -> Boolean) = endpoint
        } })
        seed()
        val file = temp.newFile("closing.bin").apply { writeText("x") }
        val screen = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // The file screen closes on the line after Send, which cancels any scope it owns.
        FriendsHub.queueSend(key, listOf(file.path))
        screen.cancel()
        withTimeout(5_000L) { FriendsHub.transfers.first { list -> list.any { it.friendKey == key } } }
        Unit
    }

    @Test fun alwaysAcceptIsSavedBeforeThePendingDecisionIsReleased() = runBlocking {
        seed()
        val dao = database.friendsDao()
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Unit>()
        replace("dao", object : FriendsDao by dao {
            override suspend fun setAutoAccept(key: String, autoAccept: Boolean) {
                writing.complete(Unit)
                try { release.await(); dao.setAutoAccept(key, autoAccept) } finally { written.complete(Unit) }
            }
        })
        val stream = OfferedStream(FriendOffer("ef".repeat(16), 4, "always.bin"))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { FriendsHub.pendingOffers.first { list -> list.any { it.friendKey == key } }.first { it.friendKey == key } }
        val receiving = async { handle(stream) }
        try {
            val offer = withTimeout(5_000L) { pending.await() }
            val offers = FriendsHub::class.java.getDeclaredField("offers").apply { isAccessible = true }.get(FriendsHub)
            val decisions = offers.javaClass.getDeclaredField("deciders").apply { isAccessible = true }.get(offers) as Map<*, *>
            val decision = decisions[offer.id] as CompletableDeferred<*>
            FriendsHub.decide(offer.id, true, always = true)
            withTimeout(5_000L) { writing.await() }
            yield()
            assertFalse(decision.isCompleted)
            release.complete(Unit)
            withTimeout(5_000L) { receiving.await(); written.await() }
            assertTrue(dao.link(key)!!.autoAccept)
        } finally {
            release.complete(Unit)
            receiving.cancelAndJoin()
            withTimeout(5_000L) { written.await() }
        }
    }

    @Test fun renameDoesNotAdoptAnUnrelatedExistingFolder() = runBlocking {
        seed()
        val original = temp.root.resolve("inbox/Alex").apply { mkdirs(); resolve("mine.bin").writeText("mine") }
        val occupied = temp.root.resolve("inbox/Occupied").apply { mkdirs(); resolve("other.bin").writeText("other") }
        assertTrue(runCatching { FriendsHub.rename(key, "Occupied") }.isFailure)
        assertEquals("Alex", database.friendsDao().link(key)!!.folderName)
        assertEquals("mine", original.resolve("mine.bin").readText())
        assertEquals("other", occupied.resolve("other.bin").readText())
    }

    @Test fun aFailedDatabaseRenameRestoresTheOriginalFolder() = runBlocking {
        seed()
        val original = temp.root.resolve("inbox/Alex").apply { mkdirs(); resolve("mine.bin").writeText("mine") }
        BundledSQLiteDriver().open(temp.root.resolve("friends.db").absolutePath).use { connection ->
            connection.prepare("CREATE TRIGGER reject_rename BEFORE INSERT ON friend_links WHEN NEW.label = 'New' BEGIN SELECT RAISE(ABORT, 'test rename failure'); END").use { it.step() }
        }
        assertTrue(runCatching { FriendsHub.rename(key, "New") }.isFailure)
        assertEquals("Alex", database.friendsDao().link(key)!!.folderName)
        assertEquals("mine", original.resolve("mine.bin").readText())
        assertFalse(temp.root.resolve("inbox/New").exists())
    }

    @Test fun renameWhileAReceiveIsWaitingDoesNotMoveItsDestination() = runBlocking {
        seed()
        val stream = OfferedStream(FriendOffer("fa".repeat(16), 4, "waiting.bin"))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { FriendsHub.pendingOffers.first { list -> list.any { it.friendKey == key } } }
        val receiving = async { handle(stream) }
        try {
            withTimeout(5_000L) { pending.await() }
            assertTrue(runCatching { FriendsHub.rename(key, "New") }.isFailure)
            assertEquals("Alex", database.friendsDao().link(key)!!.folderName)
        } finally { receiving.cancelAndJoin() }
    }

    @Test fun cancelBeforeTheDatabaseRenameRestoresTheOriginalDirectory() = runBlocking {
        seed()
        val original = temp.root.resolve("inbox/Alex").apply { mkdirs(); resolve("mine.bin").writeText("mine") }
        val dao = database.friendsDao()
        val writing = CompletableDeferred<Unit>()
        replace("dao", object : FriendsDao by dao {
            override suspend fun putLink(link: FriendLinkEntity) { writing.complete(Unit); awaitCancellation() }
        })
        val renaming = async { FriendsHub.rename(key, "New") }
        withTimeout(5_000L) { writing.await() }
        renaming.cancelAndJoin()
        assertEquals("Alex", dao.link(key)!!.folderName)
        assertEquals("mine", original.resolve("mine.bin").readText())
        assertFalse(temp.root.resolve("inbox/New").exists())
    }

    private class ListenerEndpoint : FriendsEndpoint {
        private val incoming = Channel<FriendsInbound>(64)
        suspend fun submit(inbound: FriendsInbound) { incoming.send(inbound) }
        override val id = "ab".repeat(32)
        override var isAlive = true
        var dial: suspend (String) -> IrohStream = { throw java.io.IOException("No outbound stream in this listener test") }
        var refused = false
        var connects = 0
        override fun address() = "$id||"
        override suspend fun accept(): FriendsInbound? = incoming.receiveCatching().getOrNull()
        override suspend fun open(peerId: String, addressHint: String?): IrohStream = dial(peerId)
        override fun pathInfo(peerId: String): FriendPathInfo? = null
        override suspend fun connect(peerId: String) { connects++ }
        override fun refusedBy(peerId: String) = refused
        override suspend fun close() { isAlive = false; incoming.close() }
    }

    @Test fun sendsAcrossFriendsHaveAnEightConnectionLimitAndReleaseOnRemoval() = runBlocking {
        settings.setFriendsSecretKeyBase64(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        // Counts friends dialed, so the goodbye sent to a removed friend does not count as another send.
        val opened = MutableStateFlow(emptySet<String>())
        val endpoint = ListenerEndpoint().also { endpoints += it }
        endpoint.dial = { peer -> opened.update { it + peer }; awaitCancellation() }
        replace("engineProvider", { object : FriendsEngine {
            override suspend fun start(secretKey: ByteArray, hideIp: Boolean, admit: suspend (String) -> Boolean) = endpoint
        } })
        repeat(9) { n ->
            val peer = "%064x".format(n + 1)
            extraKeys += peer
            database.friendsDao().putLink(FriendLinkEntity(peer, "Peer$n", "Friends", 1, "Peer$n"))
            assertTrue(FriendsHub.enqueueSend(peer, listOf(temp.newFile("send$n.bin"))))
        }
        withTimeout(5_000L) { opened.first { it.size >= 8 } }
        assertNull("A ninth friend must wait for a global send slot", withTimeoutOrNull(200L) { opened.first { it.size >= 9 } })
        FriendsHub.remove(extraKeys.first())
        withTimeout(5_000L) { opened.first { it.size == 9 } }
        Unit
    }

    @Test fun listenerBackpressuresAfterSixteenActiveHandlers() = runBlocking {
        settings.setFriendsSecretKeyBase64(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        val endpoint = ListenerEndpoint().also { endpoints += it }
        replace("engineProvider", { object : FriendsEngine {
            override suspend fun start(secretKey: ByteArray, hideIp: Boolean, admit: suspend (String) -> Boolean) = endpoint
        } })
        val streams = (1..17).map { n ->
            val peer = "%064x".format(n)
            extraKeys += peer
            database.friendsDao().putLink(FriendLinkEntity(peer, "Peer$n", "Friends", 1, "Peer$n"))
            OfferedStream(FriendOffer("%032x".format(n), 4, "waiting$n.bin"))
                .also { endpoint.submit(FriendsInbound(peer, it)) }
        }
        FriendsHub.maintainListener(MutableStateFlow(1L)).join()
        withTimeout(5_000L) { FriendsHub.pendingOffers.first { it.count { offer -> offer.friendKey in extraKeys } >= 16 } }
        val overflow = withTimeoutOrNull(200L) { FriendsHub.pendingOffers.first { it.count { offer -> offer.friendKey in extraKeys } >= 17 } }
        assertNull("A seventeenth handler must wait before reading an offer", overflow)
        assertEquals(0, streams.last().reads)
        FriendsHub.remove(extraKeys.first())
        withTimeout(5_000L) { FriendsHub.pendingOffers.first { it.any { offer -> offer.friendKey == extraKeys.last() } } }
        assertTrue(streams.last().reads > 0)
    }

    @Test fun serviceMaintenanceRestartsAFailedListenerWithTheSameFriendsKey() = runBlocking {
        seed()
        settings.setFriendsSecretKeyBase64(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        val keys = mutableListOf<ByteArray>()
        val engine = object : FriendsEngine {
            override suspend fun start(secretKey: ByteArray, hideIp: Boolean, admit: suspend (String) -> Boolean): FriendsEndpoint {
                keys += secretKey.copyOf()
                return ListenerEndpoint().also { endpoints += it }
            }
        }
        replace("engineProvider", { engine })
        val snapshot = MutableStateFlow(1L)
        withTimeout(5_000L) { FriendsHub.maintainListener(snapshot).join() }
        assertEquals(1, endpoints.size)
        withTimeout(5_000L) { FriendsHub.maintainListener(snapshot).join() }
        assertEquals(1, endpoints.size)
        endpoints.single().close()
        withTimeout(5_000L) { FriendsHub.maintainListener(snapshot).join() }
        assertEquals(2, endpoints.size)
        assertArrayEquals(keys[0], keys[1])
        assertEquals(Base64.getEncoder().encodeToString(keys[0]), settings.friendsSecretKeyBase64())
    }
}
