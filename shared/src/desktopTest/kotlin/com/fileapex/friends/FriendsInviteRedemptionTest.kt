package com.fileapex.friends

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import com.fileapex.friends.data.FriendInviteEntity
import com.fileapex.friends.data.FriendsDao
import com.fileapex.iroh.IrohStream
import com.fileapex.util.TimeUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FriendsInviteRedemptionTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var database: FileApexDatabase
    private lateinit var dao: FriendsDao
    private val daoField = FriendsHub::class.java.getDeclaredField("dao").apply { isAccessible = true }
    private var previousDao: Any? = null
    private val inviteId = "ab".repeat(16)
    private val secret = "cd".repeat(16)
    private val pin = "123456"

    @Before fun setUp() {
        database = Room.databaseBuilder<FileApexDatabase>(temp.root.resolve("friends.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        dao = database.friendsDao()
        previousDao = daoField.get(FriendsHub)
        daoField.set(FriendsHub, dao)
    }

    @After fun tearDown() {
        daoField.set(FriendsHub, previousDao)
        database.close()
    }

    private suspend fun seed(expiresAtMs: Long = TimeUtils.now() + 60_000L, id: String = inviteId) {
        dao.putInvite(FriendInviteEntity(id, FriendsInviteSecrets.verifier(secret, pin), "Family", expiresAtMs))
    }

    private class PairStream(payload: String) : IrohStream {
        private val bytes = payload.encodeToByteArray()
        private val input = ByteArrayInputStream(ByteBuffer.allocate(4).putInt(bytes.size).array() + bytes)
        private val output = ByteArrayOutputStream()
        override suspend fun read(max: Int): ByteArray? =
            if (input.available() == 0) null else input.readNBytes(minOf(max, input.available()))
        override suspend fun write(bytes: ByteArray) { output.write(bytes) }
        override suspend fun finish() = Unit
        override fun abort() = Unit
        fun reply(): String = output.toByteArray().drop(4).toByteArray().decodeToString()
    }

    private suspend fun redeem(peer: String, enteredPin: String = pin, id: String = inviteId): String {
        val stream = PairStream("v1\n$id\n$secret\n$enteredPin\nAlex")
        val method = FriendsHub::class.java.declaredMethods.single { it.name == "handlePair" }
            .apply { isAccessible = true }
        suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            try { method.invoke(FriendsHub, peer, stream, continuation) }
            catch (e: InvocationTargetException) { throw e.targetException }
        }
        return stream.reply()
    }

    private fun synchronizeInitialReads(parties: Int) {
        val reads = AtomicInteger()
        val ready = CompletableDeferred<Unit>()
        daoField.set(FriendsHub, object : FriendsDao by dao {
            override suspend fun invite(id: String): FriendInviteEntity? {
                val snapshot = dao.invite(id)
                val index = reads.incrementAndGet()
                if (index <= parties) {
                    if (index == parties) ready.complete(Unit)
                    ready.await()
                }
                return snapshot
            }
        })
    }

    @Test fun successfulJoinStoresTheFriendAndRejectsReplay() = runBlocking {
        seed()
        assertEquals("1", redeem("peer-one"))
        assertEquals("0", redeem("peer-two"))
        assertEquals(listOf("peer-one"), dao.observeLinks().first().map { it.friendKey })
        assertTrue(dao.invite(inviteId)!!.used)
    }

    @Test fun expiredInviteAddsNoFriend() = runBlocking {
        seed(expiresAtMs = TimeUtils.now() - 1_000L)
        assertEquals("0", redeem("peer-one"))
        assertTrue(dao.observeLinks().first().isEmpty())
    }

    @Test fun cancelledInviteCannotBeRedeemedByAnAlreadyConnectedPeer() = runBlocking {
        seed()
        val stateField = FriendsHub::class.java.getDeclaredField("inviteInternal").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val state = stateField.get(FriendsHub) as MutableStateFlow<ActiveInvite?>
        val previous = state.value
        try {
            val uri = FriendInviteLink(inviteId, secret, "${"ab".repeat(32)}||", "Family", "Alex").toUri()
            state.value = ActiveInvite(uri, pin, TimeUtils.now() + 60_000L, "Family")
            FriendsHub.cancelInvite()
            assertEquals(null, state.value)
            assertEquals("0", redeem("peer-connected-before-cancel"))
            assertTrue(dao.observeLinks().first().isEmpty())
        } finally { state.value = previous }
    }

    @Test fun simultaneousJoinsConsumeTheInviteOnlyOnce() = runBlocking {
        seed()
        synchronizeInitialReads(2)
        val replies = withTimeout(10_000L) {
            listOf("peer-one", "peer-two").map { peer -> async { redeem(peer) } }.awaitAll()
        }
        assertEquals(listOf("0", "1"), replies.sorted())
        assertEquals(1, dao.observeLinks().first().size)
        assertTrue(dao.invite(inviteId)!!.used)
    }

    @Test fun simultaneousWrongPinsAllCountAndBurnTheInvite() = runBlocking {
        seed()
        synchronizeInitialReads(3)
        withTimeout(10_000L) {
            (1..3).map { peer -> async { assertEquals("0", redeem("peer-$peer", "000000")) } }.awaitAll()
        }
        assertEquals(3, dao.invite(inviteId)!!.failedAttempts)
        assertTrue(dao.invite(inviteId)!!.used)
        assertEquals("0", redeem("peer-correct"))
        assertTrue(dao.observeLinks().first().isEmpty())
    }

    @Test fun failedInviteConsumptionDoesNotLeaveAnAllowedFriend() = runBlocking {
        seed()
        BundledSQLiteDriver().open(temp.root.resolve("friends.db").absolutePath).use { connection ->
            connection.prepare("CREATE TRIGGER reject_consumption BEFORE INSERT ON friend_invites WHEN NEW.used = 1 BEGIN SELECT RAISE(ABORT, 'test consumption failure'); END").use { it.step() }
        }
        assertTrue(runCatching { redeem("peer-one") }.isFailure)
        assertTrue(dao.observeLinks().first().isEmpty())
        assertEquals(false, dao.invite(inviteId)!!.used)
    }

    @Test fun simultaneousInvitesWithTheSameLabelGetDistinctFolders() = runBlocking {
        val otherId = "ef".repeat(16)
        seed()
        seed(id = otherId)
        synchronizeInitialReads(2)
        withTimeout(10_000L) {
            listOf(inviteId, otherId).mapIndexed { index, id ->
                async { assertEquals("1", redeem("peer-$index", id = id)) }
            }.awaitAll()
        }
        val links = dao.observeLinks().first()
        assertEquals(2, links.size)
        assertEquals(2, links.map { it.folderName.lowercase() }.toSet().size)
    }
}
