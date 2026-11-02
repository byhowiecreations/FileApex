package com.fileapex.friends.ui

import com.fileapex.friends.PendingFriendOffer
import com.fileapex.friends.transfer.FriendTransfer
import com.fileapex.friends.transfer.FriendTransferDirection
import com.fileapex.friends.transfer.FriendTransferStatus
import org.junit.Assert.*
import org.junit.Test

class FriendsQueueCountsTest {
    private fun row(id: String, status: FriendTransferStatus, receive: Boolean = false) = FriendTransfer(
        id, "friend", "Friend", if (receive) FriendTransferDirection.RECEIVE else FriendTransferDirection.SEND, "a.bin", 10, status = status
    )

    @Test fun queuedAndActiveFilesCountWhileFinishedFilesAreOnlyRecent() {
        val counts = friendQueueCounts(listOf(row("queued", FriendTransferStatus.QUEUED), row("active", FriendTransferStatus.ACTIVE), row("done", FriendTransferStatus.DONE)), emptyList())
        assertEquals(2, counts.files)
        assertEquals(0, counts.offers)
        assertEquals(1, counts.recent)
        assertEquals(2, counts.total)
        assertTrue(counts.visible)
    }

    @Test fun aBatchIsOneWaitingOfferAndIsNotCountedAgainAsAFile() {
        val offer = PendingFriendOffer("offer", "Friend", "7 files", 70, friendKey = "friend", fileCount = 7)
        val counts = friendQueueCounts(listOf(row("offer", FriendTransferStatus.WAITING, receive = true)), listOf(offer, offer))
        assertEquals(0, counts.files)
        assertEquals(1, counts.offers)
        assertEquals(1, counts.total)
    }

    @Test fun recentResultsStayDiscoverableUntilTheyAreRemovedFromTheBoard() {
        val counts = friendQueueCounts(listOf(row("done", FriendTransferStatus.DONE)), emptyList())
        assertEquals(0, counts.total)
        assertTrue(counts.visible)
        assertFalse(friendQueueCounts(emptyList(), emptyList()).visible)
    }
}
