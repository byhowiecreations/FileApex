package com.fileapex.data.bulletin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulletinPeerBackoffTest {
    private val backoff = BulletinPeerBackoff(initialDelayMs = 1_000L, maxDelayMs = 8_000L)

    @Test
    fun unknownPeerCanAttempt() {
        assertTrue(backoff.canAttempt("a", nowMs = 0L))
    }

    @Test
    fun failuresDoubleUpToCap() {
        assertEquals(1_000L, backoff.onFailure("a", nowMs = 0L))
        assertEquals(2_000L, backoff.onFailure("a", nowMs = 0L))
        assertEquals(4_000L, backoff.onFailure("a", nowMs = 0L))
        assertEquals(8_000L, backoff.onFailure("a", nowMs = 0L))
        assertEquals(8_000L, backoff.onFailure("a", nowMs = 0L))
    }

    @Test
    fun blockedUntilDelayElapses() {
        backoff.onFailure("a", nowMs = 10_000L)
        assertFalse(backoff.canAttempt("a", nowMs = 10_500L))
        assertTrue(backoff.canAttempt("a", nowMs = 11_000L))
    }

    @Test
    fun successResetsAndPeersAreIndependent() {
        backoff.onFailure("a", nowMs = 0L)
        backoff.onFailure("a", nowMs = 0L)
        assertTrue(backoff.canAttempt("b", nowMs = 0L))
        backoff.onSuccess("a")
        assertTrue(backoff.canAttempt("a", nowMs = 0L))
        assertEquals(1_000L, backoff.onFailure("a", nowMs = 0L))
    }
}
