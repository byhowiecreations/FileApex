package com.fileapex.friends

import com.fileapex.friends.transfer.FriendTransfer
import com.fileapex.friends.transfer.FriendTransferDirection
import com.fileapex.friends.transfer.FriendTransferStatus
import com.fileapex.friends.transfer.FriendsSendQueue
import com.fileapex.friends.transfer.FriendsTransferBoard
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendsTransferBoardTest {
    private var clock = 1_000L
    private val seen = mutableListOf<FriendTransfer>()
    private val board = FriendsTransferBoard(CoroutineScope(SupervisorJob() + Dispatchers.Default), now = { clock }, minGapMs = 150, keepFinishedMs = 60_000, onChange = { seen += it })
    private fun transfer(id: String = "t1") = FriendTransfer(id, "k", "Ann", FriendTransferDirection.SEND, "a.bin", 1000)

    @Test fun progressIsCutToOneUpdatePerGapButStatusAlwaysGoesThrough() {
        board.put(transfer())
        board.status("t1", FriendTransferStatus.ACTIVE)
        clock += 50; board.progress("t1", 100)
        assertEquals(0L, board.get("t1")!!.doneBytes)
        clock += 200; board.progress("t1", 300)
        assertEquals(300L, board.get("t1")!!.doneBytes)
        board.status("t1", FriendTransferStatus.VERIFYING)
        assertEquals(FriendTransferStatus.VERIFYING, board.get("t1")!!.status)
    }

    @Test fun speedIsAveragedOverTheActiveTime() {
        board.put(transfer()); board.status("t1", FriendTransferStatus.ACTIVE)
        clock += 1_000; board.progress("t1", 500)
        assertEquals(500L, board.get("t1")!!.speedBps)
    }

    @Test fun doneFillsTheBarAndFinishedStatusesAreMarked() {
        board.put(transfer()); board.status("t1", FriendTransferStatus.DONE)
        assertEquals(1000L, board.get("t1")!!.doneBytes)
        assertTrue(board.get("t1")!!.status.finished)
        assertFalse(FriendTransferStatus.ACTIVE.finished)
    }

    @Test fun dropRemovesTheTransfer() {
        board.put(transfer()); board.drop("t1")
        assertNull(board.get("t1"))
    }

    @Test fun queueIsFirstInFirstOutPerFriendAndCancelRemovesAWaitingFile() {
        val q = FriendsSendQueue()
        val f = File("x")
        q.add(FriendsSendQueue.Item("1", "a", f)); q.add(FriendsSendQueue.Item("2", "b", f)); q.add(FriendsSendQueue.Item("3", "a", f))
        assertEquals(listOf("1", "3"), q.idsFor("a"))
        assertEquals("1", q.take("a")!!.id)
        assertTrue(q.remove("3"))
        assertFalse(q.remove("3"))
        assertNull(q.take("a"))
        assertEquals("2", q.take("b")!!.id)
    }
}
