package com.fileapex.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PeerExchangeTest {
    private val logged = mutableListOf<String>()
    private val exchange = PeerExchange { logged += it }

    private fun ok(body: String = "x", status: Int = 200) = PeerBoundHttpResponse(status, body)

    private fun answer(okOnly: Boolean = true, call: suspend () -> PeerBoundHttpResponse) =
        runBlocking { exchange.answer("peer-1", "chain pull", okOnly, call) { it.uppercase() } }

    @Test fun aGoodAnswerIsDecodedAndNothingIsLogged() {
        assertEquals("HELLO", answer { ok("hello") })
        assertTrue(logged.isEmpty())
    }

    @Test fun anUnreachablePeerIsNullAndLoggedWithItsId() {
        assertNull(answer { throw PeerUnreachableException("host 192.168.1.9 is down") })
        assertEquals(1, logged.size)
        assertTrue(logged.single().contains("peer-1") && logged.single().contains("chain pull"))
        assertTrue("no address in the log: $logged", !logged.single().contains("192.168"))
    }

    @Test fun aPolicyRefusalIsNullAndLogged() {
        assertNull(answer { throw IllegalArgumentException("Peer host must be a private LAN address: 8.8.8.8") })
        assertTrue(logged.single().contains("peer-1") && !logged.single().contains("8.8.8.8"))
    }

    @Test fun aNon2xxIsNullAndLoggedWithTheStatusWhenOnlyOkIsAccepted() {
        assertNull(answer(okOnly = true) { ok(status = 403) })
        assertTrue(logged.single().contains("403"))
    }

    @Test fun aNon2xxIsStillDecodedWhenTheRefusalCarriesAnAnswer() {
        assertEquals("REFUSED", answer(okOnly = false) { ok("refused", 403) })
        assertTrue(logged.isEmpty())
    }

    @Test fun anUnreadableBodyIsNullAndLoggedWithTheStatus() {
        val result = runBlocking { exchange.answer<String>("peer-1", "chain pull", false, { ok("<html>", 401) }) { throw SerializationException("bad") } }
        assertNull(result)
        assertTrue(logged.single().contains("401"))
    }

    @Test fun cancellationIsNeverSwallowed() {
        try {
            answer { throw CancellationException("stop") }
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            assertTrue(logged.isEmpty())
        }
    }

    @Test fun anUnexpectedFailureIsABugAndPropagates() {
        try {
            answer { throw NullPointerException("bug") }
            fail("a bug must propagate")
        } catch (expected: NullPointerException) {
            assertTrue(logged.isEmpty())
        }
    }

    @Test fun theSameFailureIsLoggedOnceUntilThePeerAnswersAgain() {
        repeat(3) { answer { throw PeerUnreachableException("down") } }
        assertEquals(1, logged.size)
        answer { ok() }
        answer { throw PeerUnreachableException("down") }
        assertEquals(2, logged.size)
    }

    @Test fun aDifferentFailureIsLoggedAtOnce() {
        answer { throw PeerUnreachableException("down") }
        answer { ok(status = 401) }
        assertEquals(2, logged.size)
    }
}
