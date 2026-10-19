package com.fileapex.iroh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** A real endpoint: watching its address must not need the library's async runtime from the caller's thread. */
class IrohRealAddressWatchTest {
    @Test
    fun watchingTheAddressOnARealEndpointDoesNotThrow() = runBlocking {
        val link = IrohEndpointLink.bind(relay = false, bindAddr = "127.0.0.1:0")
        try {
            link.onAddressChanged { }
            assertTrue(link.address().startsWith(link.id))
        } finally {
            link.close()
        }
    }
}
