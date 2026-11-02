package com.fileapex.friends

import com.fileapex.friends.transfer.copyFriendSharedSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FriendSharedCopyTest {
    @Test fun cancellationAfterAReadDoesNotWriteAnotherChunk() = runBlocking {
        val output = ByteArrayOutputStream()
        val copying = launch(start = CoroutineStart.LAZY) {
            val context = currentCoroutineContext()
            val input = object : InputStream() {
                override fun read(): Int = error("Use bounded reads")
                override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                    context.cancel()
                    bytes[off] = 42
                    return 1
                }
            }
            copyFriendSharedSource(input, output)
        }
        copying.start()
        withTimeout(2_000) { copying.join() }
        assertTrue(copying.isCancelled)
        assertEquals(0, output.size())
    }

    @Test fun stagingUsesBoundedBuffersAndPreservesEmptyFiles() = runBlocking {
        val bytes = ByteArray(100_003) { (it % 251).toByte() }
        var largestRead = 0
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                largestRead = maxOf(largestRead, len)
                return super.read(buffer, off, len)
            }
        }
        val output = ByteArrayOutputStream()
        copyFriendSharedSource(input, output)
        assertArrayEquals(bytes, output.toByteArray())
        assertTrue(largestRead in 1..32_768)
        val empty = ByteArrayOutputStream()
        copyFriendSharedSource(ByteArrayInputStream(byteArrayOf()), empty)
        assertEquals(0, empty.size())
    }
}
