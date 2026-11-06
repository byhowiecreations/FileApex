package com.fileapex.iroh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohRelayFallbackTest {
    private var now = 1_000_000L
    private val fallback = IrohRelayFallback { now }
    private val minute = 60_000L

    @Test
    fun theCustomRelayIsUsedUntilItFails() {
        assertTrue(fallback.shouldUseCustom())
        assertFalse(fallback.isFallback)
        assertFalse(fallback.retryDue())
    }

    @Test
    fun afterAFailureTheDeviceStaysOnN0UntilTheFirstRetryIsDue() {
        fallback.customFailed()
        assertTrue(fallback.isFallback)
        assertFalse(fallback.shouldUseCustom())
        now += 5 * minute - 1
        assertFalse(fallback.shouldUseCustom())
        now += 1
        assertTrue(fallback.retryDue())
        assertTrue(fallback.shouldUseCustom())
    }

    @Test
    fun eachFailedRetryWaitsLongerUpToThirtyMinutes() {
        val waits = mutableListOf<Long>()
        repeat(6) {
            val failedAt = now
            fallback.customFailed()
            while (!fallback.retryDue()) now += minute
            waits += (now - failedAt) / minute
        }
        assertEquals(listOf(5L, 10L, 20L, 30L, 30L, 30L), waits)
    }

    @Test
    fun aWorkingCustomRelayEndsTheFallbackAndResetsTheWait() {
        fallback.customFailed()
        now += 5 * minute
        fallback.customWorked()
        assertFalse(fallback.isFallback)
        assertTrue(fallback.shouldUseCustom())
        fallback.customFailed()
        now += 5 * minute
        assertTrue("wait is back to five minutes", fallback.retryDue())
    }

    @Test
    fun savingADifferentRelayForgetsTheFallbackAtOnce() {
        fallback.customFailed()
        fallback.reset()
        assertFalse(fallback.isFallback)
        assertTrue(fallback.shouldUseCustom())
    }

    @Test
    fun aFailureWhileAlreadyInFallbackDoesNotShortenTheWait() {
        fallback.customFailed()
        now += 5 * minute
        fallback.customFailed()
        now += 9 * minute
        assertFalse(fallback.retryDue())
        now += minute
        assertTrue(fallback.retryDue())
    }

    @Test
    fun followingANewRelayForgetsTheFallbackButTheSameRelayDoesNot() {
        val relay = IrohRelayConfig("https://relay.example.com", "t")
        fallback.follow(relay)
        fallback.customFailed()
        fallback.follow(relay)
        assertTrue(fallback.isFallback)
        fallback.follow(IrohRelayConfig("https://relay.example.com", "new-token"))
        assertFalse(fallback.isFallback)
        fallback.customFailed()
        fallback.follow(null)
        assertFalse(fallback.isFallback)
    }
}
