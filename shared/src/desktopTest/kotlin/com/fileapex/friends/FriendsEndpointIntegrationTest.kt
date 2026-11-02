package com.fileapex.friends

import com.fileapex.friends.transfer.*
import com.fileapex.iroh.IrohStream
import java.security.SecureRandom
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FriendsEndpointIntegrationTest {
    @get:Rule val temp = TemporaryFolder()

    private class Stream : IrohStream {
        var aborts = 0
        override suspend fun read(max: Int): ByteArray? = null
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun finish() = Unit
        override fun abort() { aborts++ }
    }

    @Test fun aFullNativeInboxBackpressuresUntilTheHubAccepts() = runBlocking {
        val inbox = FriendsInbox(capacity = 1)
        val first = FriendsInbound("a", Stream())
        val second = FriendsInbound("b", Stream())
        try {
            inbox.send(first)
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { inbox.send(second) }
            assertFalse(waiting.isCompleted)
            assertSame(first, inbox.accept())
            withTimeout(5_000L) { waiting.await() }
            assertSame(second, inbox.accept())
        } finally { inbox.close() }
    }

    @Test fun closingTheInboxAbortsQueuedAndBlockedStreams() = runBlocking {
        val inbox = FriendsInbox(capacity = 1)
        val first = Stream()
        val second = Stream()
        inbox.send(FriendsInbound("a", first))
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { inbox.send(FriendsInbound("b", second)) }
        inbox.close()
        withTimeout(5_000L) { waiting.join() }
        assertEquals(1, first.aborts)
        assertEquals(1, second.aborts)
        assertNull(inbox.accept())
    }

    @Test fun nativeEndpointsExchangeAChecksummedFileUsingEphemeralIdentities() = runBlocking {
        withTimeout(30_000L) {
            val engine = checkNotNull(embeddedFriendsEngine())
            val receiver = engine.start(SecureRandom().generateSeed(32), false) { true }
            var sender: FriendsEndpoint? = null
            try {
                val sendingEndpoint = engine.start(SecureRandom().generateSeed(32), false) { true }
                sender = sendingEndpoint
                val source = temp.newFile("source.bin").apply { writeBytes(ByteArray(128 * 1024) { (it % 251).toByte() }) }
                val receiving = async {
                    val inbound = checkNotNull(receiver.accept())
                    assertEquals(sendingEndpoint.id, inbound.peerId)
                    FriendsReceiver(temp.newFolder("received"), 1024 * 1024) { true }.handle(inbound.stream, inbound.peerId)
                }
                val stream = sendingEndpoint.open(receiver.id, receiver.address())
                assertEquals(FriendSendResult.Done, FriendsSender.send(stream, source, "ab".repeat(16)))
                val result = receiving.await() as FriendReceiveResult.Received
                assertArrayEquals(source.readBytes(), result.file.readBytes())
            } finally {
                sender?.close()
                receiver.close()
                assertFalse(receiver.isAlive)
            }
        }
    }

    @Test fun aPeerThatRefusesUsAsNotAFriendIsSeenAsSuch() = runBlocking {
        withTimeout(30_000L) {
            val engine = checkNotNull(embeddedFriendsEngine())
            val refuser = engine.start(SecureRandom().generateSeed(32), false) { false }
            val caller = engine.start(SecureRandom().generateSeed(32), false) { true }
            try {
                runCatching { caller.open(refuser.id, refuser.address()) }
                while (!caller.refusedBy(refuser.id)) delay(50)
                assertFalse(caller.refusedBy("00".repeat(32)))
            } finally {
                caller.close()
                refuser.close()
            }
        }
    }
}
