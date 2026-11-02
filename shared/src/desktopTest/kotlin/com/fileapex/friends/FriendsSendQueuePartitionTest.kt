package com.fileapex.friends

import com.fileapex.friends.transfer.*
import java.io.IOException
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FriendsSendQueuePartitionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun aLargeSelectionUsesValidBoundedBatchesWithoutDroppingFiles() {
        val files = (1..501).map { temp.newFile("file$it.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val board = FriendsTransferBoard(scope)
            val queue = FriendsSendQueue()
            queue.enqueue("friend", "Alex", files, false, board, scope) { throw IOException("Enqueue must not connect") }
            val items = generateSequence { queue.take("friend") }.toList()
            assertEquals(files, items.map { it.file })
            assertEquals(501, board.transfers.value.size)
            assertTrue(board.transfers.value.all { it.status == FriendTransferStatus.QUEUED })
            val batch = checkNotNull(items.first().batch)
            assertEquals(500, batch.batch.count)
            assertEquals(1500L, batch.batch.totalBytes)
            assertTrue(items.take(500).all { it.batch === batch })
            assertNull(items.last().batch)
            assertEquals(batch.batch, FriendsBatchWire.decode(FriendsBatchWire.encode(batch.batch)))
        } finally { scope.cancel() }
    }

    @Test fun overflowingBatchSizesCannotLeaveAnEarlierBatchPartiallyQueued() {
        val small = temp.newFile("small.bin").apply { writeBytes(byteArrayOf(1)) }
        val huge = (1..2).map { n -> object : File(temp.root, "huge$n.bin") { override fun length() = Long.MAX_VALUE } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val board = FriendsTransferBoard(scope)
            val queue = FriendsSendQueue()
            val failure = runCatching { queue.enqueue("friend", "Alex", List(500) { small } + huge, false, board, scope) { throw IOException("Must not connect") } }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(queue.idsFor("friend").isEmpty())
            assertTrue(board.transfers.value.isEmpty())
        } finally { scope.cancel() }
    }
}
