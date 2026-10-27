package com.fileapex.security.tls

import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * stop() closes every open socket while connection handlers remove theirs. Kotlin's Collection.toList() reads size
 * and then calls iterator().next() when the size is 1, so a removal in between threw NoSuchElementException and
 * failed whichever test was tearing a server down.
 */
class TlsFrontServerCloseAllTest {
    private class CountingSocket : Socket() {
        var closed = 0
        override fun close() {
            closed++
            super.close()
        }
    }

    @Test
    fun aSetThatEmptiesAfterItsSizeIsReadDoesNotThrow() {
        val emptiesUnderneath = object : AbstractCollection<Socket>() {
            override val size: Int get() = 1
            override fun iterator(): Iterator<Socket> = emptyList<Socket>().iterator()
        }
        TlsFrontServer.closeAll(emptiesUnderneath)
    }

    @Test
    fun everyOpenSocketIsClosed() {
        val sockets = List(3) { CountingSocket() }
        TlsFrontServer.closeAll(sockets)
        assertEquals(listOf(1, 1, 1), sockets.map { it.closed })
        assertTrue(sockets.all { it.isClosed })
    }

    @Test
    fun aSocketThatFailsToCloseDoesNotStopTheRest() {
        val failing = object : Socket() {
            override fun close() = throw java.io.IOException("close failed")
        }
        val after = CountingSocket()
        TlsFrontServer.closeAll(listOf(failing, after))
        assertEquals(1, after.closed)
    }
}
