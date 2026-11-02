package com.fileapex.friends

import com.fileapex.friends.transfer.FriendFailure
import com.fileapex.friends.transfer.FriendTransfer
import com.fileapex.friends.transfer.FriendTransferDirection
import com.fileapex.friends.transfer.FriendTransferStatus
import com.fileapex.friends.transfer.FriendsTransferBoard
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Test

class FriendFailureTest {
    @Test fun aSendThatNeverReachedTheFriendIsNamedUnreachable() {
        assertEquals(FriendFailure.UNREACHABLE, FriendFailure.of(IOException("friend open: no route to host")))
        assertEquals(FriendFailure.UNREACHABLE, FriendFailure.of(IOException("friend open failed")))
    }

    @Test fun silenceFromTheFriendIsNamedTimeout() {
        assertEquals(FriendFailure.TIMEOUT, FriendFailure.of(IOException("friend connect timed out")))
        assertEquals(FriendFailure.TIMEOUT, FriendFailure.of(IOException("friend reply timed out")))
        assertEquals(FriendFailure.TIMEOUT, FriendFailure.of(IOException("friend write timed out")))
    }

    @Test fun aDroppedLinkIsNamedLostEvenWhenItsMessageMentionsATimeout() {
        assertEquals(FriendFailure.LOST, FriendFailure.of(IOException("friend stream ended or timed out")))
        assertEquals(FriendFailure.LOST, FriendFailure.of(IOException("Connection reset")))
    }

    @Test fun anUnreadableFileIsNamedFile() {
        assertEquals(FriendFailure.FILE, FriendFailure.of(FileNotFoundException("x (Permission denied)")))
        assertEquals(FriendFailure.FILE, FriendFailure.of(NoSuchFileException("x")))
        assertEquals(FriendFailure.FILE, FriendFailure.of(AccessDeniedException("x")))
    }

    @Test fun theBoardKeepsWhyAFileFailed() {
        val board = FriendsTransferBoard(CoroutineScope(Job() + Dispatchers.Unconfined))
        board.put(FriendTransfer("t", "k", "Friend", FriendTransferDirection.SEND, "a.bin", 10))
        board.status("t", FriendTransferStatus.FAILED, FriendFailure.UNREACHABLE)
        assertEquals(FriendFailure.UNREACHABLE, board.get("t")!!.note)
        assertEquals(FriendTransferStatus.FAILED, board.get("t")!!.status)
    }
}
