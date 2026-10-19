package com.fileapex.domain.pairing

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingConfirmationBoardTest {
    private val join = PendingJoin(deviceId = "joiner-1", deviceName = "Phone", checkCode = "862 634")

    @Test
    fun theHostUserAcceptsAndTheJoinerIsLetIn() = runBlocking {
        val board = PairingConfirmationBoard()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { board.askHost(join, timeoutMs = 5_000L) }
        assertEquals(join, board.pendingJoin.value)
        board.decideHost(accepted = true)
        assertEquals(true, answer.await())
        assertNull(board.pendingJoin.value)
    }

    @Test
    fun theHostUserDeclines() = runBlocking {
        val board = PairingConfirmationBoard()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { board.askHost(join, timeoutMs = 5_000L) }
        board.decideHost(accepted = false)
        assertEquals(false, answer.await())
        assertNull(board.pendingJoin.value)
    }

    @Test
    fun noAnswerInTimeCountsAsDeclined() = runBlocking {
        val board = PairingConfirmationBoard()
        assertEquals(false, board.askHost(join, timeoutMs = 50L))
        assertNull(board.pendingJoin.value)
    }

    @Test
    fun aSecondJoinerWhileOneIsWaitingIsTurnedAway() = runBlocking {
        val board = PairingConfirmationBoard()
        val first = async(start = CoroutineStart.UNDISPATCHED) { board.askHost(join, timeoutMs = 5_000L) }
        yield()
        assertNull(board.askHost(join.copy(deviceId = "joiner-2"), timeoutMs = 5_000L))
        assertEquals(join, board.pendingJoin.value)
        board.decideHost(accepted = true)
        assertTrue(first.await() == true)
    }

    @Test
    fun theJoinerUserConfirmsTheCodeBeforeAnythingIsSent() = runBlocking {
        val board = PairingConfirmationBoard()
        val check = JoinerCheck(hostName = "Mac", checkCode = "862 634")
        val answer = async(start = CoroutineStart.UNDISPATCHED) { board.askJoiner(check) }
        assertEquals(check, board.joinerCheck.value)
        board.decideJoiner(confirmed = true)
        assertTrue(answer.await())
        assertNull(board.joinerCheck.value)
    }

    @Test
    fun theJoinerUserCancels() = runBlocking {
        val board = PairingConfirmationBoard()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { board.askJoiner(JoinerCheck("Mac", "862 634")) }
        board.decideJoiner(confirmed = false)
        assertFalse(answer.await())
    }

    @Test
    fun aDecisionWithNobodyAskingIsIgnored() {
        val board = PairingConfirmationBoard()
        board.decideHost(true)
        board.decideJoiner(true)
        assertNull(board.pendingJoin.value)
        assertNull(board.joinerCheck.value)
    }
}
