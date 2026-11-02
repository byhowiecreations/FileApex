package com.fileapex.friends

import com.fileapex.friends.transfer.FriendsWire
import com.fileapex.iroh.IrohStream
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class FriendsWireTimeoutTest {
    private val silent = object : IrohStream {
        override suspend fun read(max: Int): ByteArray? = awaitCancellation()
        override suspend fun write(bytes: ByteArray): Unit = awaitCancellation()
        override suspend fun finish() = Unit
        override fun abort() = Unit
    }

    @Test fun anUnresponsivePeerCannotHoldAReplyWaitForever() = runBlocking {
        val result = withTimeout(1_000L) { runCatching { FriendsWire.readFrame(silent, timeoutMs = 25L) } }
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test fun aPeerThatStopsReadingCannotHoldAWriteForever() = runBlocking {
        val result = withTimeout(1_000L) { runCatching { FriendsWire.writeRaw(silent, byteArrayOf(1), timeoutMs = 25L) } }
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test fun callerCancellationRemainsCancellationInsteadOfATransferFailure() = runBlocking {
        val waiting = async { FriendsWire.readFrame(silent, timeoutMs = 10_000L) }
        waiting.cancelAndJoin()
        assertTrue(waiting.isCancelled)
    }
}
