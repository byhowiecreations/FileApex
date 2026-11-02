package com.fileapex.friends

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.FileApexDatabase
import com.fileapex.friends.data.FriendLinkEntity
import com.fileapex.friends.transfer.FriendSendIntake
import com.fileapex.friends.transfer.FriendSendLanes
import com.fileapex.friends.ui.friendFiles
import com.fileapex.platform.sendTrayFriends
import com.fileapex.friends.transfer.FriendTransfer
import com.fileapex.friends.transfer.FriendTransferDirection
import com.fileapex.friends.transfer.FriendTransferStatus
import com.fileapex.friends.transfer.FriendsSendQueue
import com.fileapex.friends.transfer.FriendsTransferBoard
import com.fileapex.iroh.IrohStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FriendsRemovalTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var database: FileApexDatabase
    private var previousDao: Any? = null
    private val daoField = FriendsHub::class.java.getDeclaredField("dao").apply { isAccessible = true }
    private val board get() = field("board") as FriendsTransferBoard
    private val queue get() = field("sendQueue") as FriendsSendQueue
    private val ids = mutableListOf<String>()

    private fun field(name: String): Any {
        val owner = if (name == "pendingInternal" || name == "deciders") field("offers") else FriendsHub
        return owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> map(name: String): MutableMap<String, T> = field(name) as MutableMap<String, T>

    @Suppress("UNCHECKED_CAST")
    private val pending get() = field("pendingInternal") as MutableStateFlow<List<PendingFriendOffer>>

    @Suppress("UNCHECKED_CAST")
    private val canceled get() = field("userCanceled") as MutableSet<String>

    @Before fun setUp() {
        database = Room.databaseBuilder<FileApexDatabase>(temp.root.resolve("friends.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        previousDao = daoField.get(FriendsHub)
        daoField.set(FriendsHub, database.friendsDao())
    }

    @After fun tearDown() {
        ids.forEach { id ->
            map<Job>("sendJobs").remove(id)?.cancel()
            map<Job>("receiveJobs").remove(id)?.cancel()
            map<IrohStream>("streams").remove(id)
            map<File>("partFiles").remove(id)
            map<CompletableDeferred<Boolean>>("deciders").remove(id)?.cancel()
            queue.remove(id)
            board.drop(id)
            canceled.remove(id)
        }
        pending.value = pending.value.filterNot { it.id in ids }
        daoField.set(FriendsHub, previousDao)
        database.close()
    }

    private fun transfer(id: String, friend: String, status: FriendTransferStatus, receive: Boolean = false) {
        ids += id
        board.put(FriendTransfer(id, friend, friend, if (receive) FriendTransferDirection.RECEIVE else FriendTransferDirection.SEND, "$id.bin", 4, status = status))
    }

    private class Stream : IrohStream {
        var aborted = false
        override suspend fun read(max: Int): ByteArray? = null
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun finish() = Unit
        override fun abort() { aborted = true }
    }

    @Test fun repeatedRemovalClearsQueuedOwnedFilesWithoutTouchingAnotherFriend() = runBlocking {
        val owned = temp.newFolder("owned").resolve("send.bin").apply { writeText("test") }
        val other = temp.newFile("other.bin").apply { writeText("keep") }
        transfer("queued-remove", "removed", FriendTransferStatus.QUEUED)
        transfer("queued-keep", "other", FriendTransferStatus.QUEUED)
        queue.add(FriendsSendQueue.Item("queued-remove", "removed", owned, owned = true))
        queue.add(FriendsSendQueue.Item("queued-keep", "other", other))
        FriendsHub.remove("removed")
        FriendsHub.remove("removed")
        assertTrue(queue.idsFor("removed").isEmpty())
        assertFalse(owned.exists())
        assertTrue(other.exists())
        assertEquals(listOf("queued-keep"), queue.idsFor("other"))
        assertEquals(FriendTransferStatus.CANCELED, board.get("queued-remove")!!.status)
        assertEquals(FriendTransferStatus.QUEUED, board.get("queued-keep")!!.status)
    }

    @Test fun removalStopsActiveSendAndReceiveBeforeDeletingTheirParts() = runBlocking {
        transfer("active-send", "removed", FriendTransferStatus.ACTIVE)
        transfer("active-receive", "removed", FriendTransferStatus.ACTIVE, receive = true)
        val sending = Job()
        val receiving = Job()
        val sendStream = Stream()
        val receiveStream = Stream()
        val part = temp.newFile("incoming.part").apply { writeText("part") }
        map<Job>("sendJobs")["active-send"] = sending
        map<Job>("receiveJobs")["active-receive"] = receiving
        map<IrohStream>("streams")["active-send"] = sendStream
        map<IrohStream>("streams")["active-receive"] = receiveStream
        map<File>("partFiles")["active-receive"] = part
        FriendsHub.remove("removed")
        assertTrue(sending.isCancelled)
        assertTrue(receiving.isCancelled)
        assertTrue(sendStream.aborted)
        assertTrue(receiveStream.aborted)
        assertFalse(part.exists())
        assertEquals(FriendTransferStatus.CANCELED, board.get("active-send")!!.status)
        assertEquals(FriendTransferStatus.CANCELED, board.get("active-receive")!!.status)
    }

    @Test fun removalDeclinesOnlyThatFriendsPendingOffers() = runBlocking {
        ids += listOf("offer-remove", "offer-keep")
        val removed = CompletableDeferred<Boolean>()
        val kept = CompletableDeferred<Boolean>()
        map<CompletableDeferred<Boolean>>("deciders")["offer-remove"] = removed
        map<CompletableDeferred<Boolean>>("deciders")["offer-keep"] = kept
        pending.value = listOf(
            PendingFriendOffer("offer-remove", "Removed", "a.bin", 4, friendKey = "removed"),
            PendingFriendOffer("offer-keep", "Kept", "b.bin", 4, friendKey = "other")
        )
        FriendsHub.remove("removed")
        assertTrue(removed.isCompleted)
        assertFalse(removed.await())
        assertFalse(kept.isCompleted)
        assertEquals(listOf("offer-keep"), pending.value.map { it.id })
    }

    @Test fun removalPreservesCompletedFilesAndFinishedRows() = runBlocking {
        val complete = temp.newFile("complete.bin").apply { writeText("keep") }
        transfer("done-keep", "removed", FriendTransferStatus.DONE)
        FriendsHub.remove("removed")
        assertEquals("keep", complete.readText())
        assertEquals(FriendTransferStatus.DONE, board.get("done-keep")!!.status)
    }

    @Test fun cancelOfAFinishedOrUnknownTransferIsANoOp() {
        transfer("done-cancel", "removed", FriendTransferStatus.DONE)
        FriendsHub.cancel("done-cancel")
        FriendsHub.cancel("unknown-cancel")
        ids += "unknown-cancel"
        assertFalse(canceled.contains("done-cancel"))
        assertFalse(canceled.contains("unknown-cancel"))
        assertEquals(FriendTransferStatus.DONE, board.get("done-cancel")!!.status)
    }

    @Test fun stagingFailureRemovesEarlierCopiesAndPreservesTheSource() = runBlocking {
        val staging = File(System.getProperty("java.io.tmpdir"), "fileapex-friends-outbox")
        val before = staging.listFiles()?.toSet().orEmpty()
        val source = temp.newFile("source.bin").apply { writeText("source") }
        try {
            assertTrue(runCatching { FriendsHub.enqueueShared(listOf("removed"), listOf(source, temp.root.resolve("missing.bin"))) }.isFailure)
            assertEquals(before, staging.listFiles()?.toSet().orEmpty())
            assertEquals("source", source.readText())
        } finally {
            staging.listFiles()?.filterNot { it in before }?.forEach { it.deleteRecursively() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun aLaterShareStagingFailureReportsTheFriendsAlreadyQueued() = runBlocking {
        val first = "share-first"
        val second = "share-second"
        val dao = database.friendsDao()
        listOf(first, second).forEach { dao.putLink(FriendLinkEntity(it, it, "Friends", 1, it)) }
        val laneCounts = FriendSendLanes::class.java.getDeclaredField("lanes").apply { isAccessible = true }
            .get(field("lanes")) as MutableMap<String, Int>
        laneCounts[first] = 4
        laneCounts[second] = 4
        val source = temp.newFile("share-source.bin").apply { writeText("source") }
        daoField.set(FriendsHub, object : com.fileapex.friends.data.FriendsDao by dao {
            override suspend fun link(key: String): FriendLinkEntity? {
                if (key == first) assertTrue(source.delete())
                return dao.link(key)
            }
        })
        try {
            val failure = runCatching { FriendsHub.enqueueShared(listOf(first, second), listOf(source)) }.exceptionOrNull()
            assertTrue(failure is com.fileapex.friends.transfer.FriendSharedQueueException)
            assertEquals(setOf(first), (failure as com.fileapex.friends.transfer.FriendSharedQueueException).acceptedFriends)
            assertEquals(1, queue.idsFor(first).size)
            assertTrue(queue.idsFor(second).isEmpty())
        } finally {
            queue.idsFor(first).forEach { id ->
                ids += id
                queue.removeItem(id)?.let { com.fileapex.friends.transfer.deleteOwnedFriendFile(it.file) }
            }
            laneCounts.remove(first); laneCounts.remove(second)
        }
    }

    @Test fun aStagingCleanupFailureDoesNotLeaveActiveWorkRunning() = runBlocking {
        val nonEmpty = temp.newFolder("undeletable-staging").apply { resolve("child").writeText("test") }
        transfer("cleanup-queued", "removed", FriendTransferStatus.QUEUED)
        transfer("cleanup-active", "removed", FriendTransferStatus.ACTIVE)
        queue.add(FriendsSendQueue.Item("cleanup-queued", "removed", nonEmpty, owned = true))
        val job = Job()
        val stream = Stream()
        map<Job>("sendJobs")["cleanup-active"] = job
        map<IrohStream>("streams")["cleanup-active"] = stream
        assertTrue(runCatching { FriendsHub.remove("removed") }.isFailure)
        assertTrue(job.isCancelled)
        assertTrue(stream.aborted)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun checkIntake(send: suspend (List<String>) -> Unit) {
        database.friendsDao().putLink(FriendLinkEntity("intake", "Intake", "Friends", 1, "Intake"))
        val laneCounts = FriendSendLanes::class.java.getDeclaredField("lanes").apply { isAccessible = true }
            .get(field("lanes")) as MutableMap<String, Int>
        laneCounts["intake"] = 4
        val source = temp.newFile("intake.bin").apply { writeText("keep") }
        try {
            kotlinx.coroutines.coroutineScope {
                val rejection = async(start = CoroutineStart.UNDISPATCHED) { FriendSendIntake.dropped.first() }
                try {
                    send(listOf(source.path, temp.root.path, temp.root.resolve("missing").path))
                    val rows = board.transfers.value.filter { it.friendKey == "intake" }
                    assertEquals(listOf(source.name), rows.map { it.fileName })
                    assertEquals(FriendSendIntake.Drop.NOT_A_FILE, withTimeout(1_000L) { rejection.await() })
                    assertEquals("keep", source.readText())
                } finally { rejection.cancel() }
            }
        } finally {
            ids += queue.idsFor("intake")
            laneCounts.remove("intake")
        }
    }

    @Test fun centralEnqueueReportsAndRejectsInvalidFiles() = runBlocking {
        checkIntake { FriendsHub.enqueueSend("intake", it.map(::File)) }
    }

    @Test fun trayIntakeReportsSkippedFoldersAndMissingPaths() = runBlocking {
        checkIntake { sendTrayFriends(listOf("friend:intake"), it) }
    }

    @Test fun friendCardDropReportsSkippedFoldersAndMissingPaths() = runBlocking {
        checkIntake { FriendsHub.enqueueSend("intake", friendFiles(it)) }
    }

    @Test fun removingAFriendPreservesCompletedFilesWithAPartExtension() = runBlocking {
        val originalHome = System.getProperty("user.home")
        val folder = temp.root.resolve("Downloads/FileApex/Kept").apply { mkdirs() }
        val completed = folder.resolve("document.part").apply { writeText("complete") }
        database.friendsDao().putLink(FriendLinkEntity("part-file", "Kept", "Friends", 1, "Kept"))
        try {
            System.setProperty("user.home", temp.root.absolutePath)
            FriendsHub.remove("part-file")
            assertTrue(completed.exists())
            assertEquals("complete", completed.readText())
            assertNull(database.friendsDao().link("part-file"))
        } finally {
            if (originalHome == null) System.clearProperty("user.home") else System.setProperty("user.home", originalHome)
        }
    }
}
