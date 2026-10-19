package com.fileapex.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthFailureThrottleTest {
    @Test
    fun aSourceUnderTheLimitIsNotThrottled() {
        val throttle = AuthFailureThrottle(limit = 3, windowMs = 1_000L)
        repeat(3) { assertFalse(throttle.hitAndCheck("a", 100L)) }
    }

    @Test
    fun theRequestOverTheLimitIsThrottled() {
        val throttle = AuthFailureThrottle(limit = 3, windowMs = 1_000L)
        repeat(3) { throttle.hitAndCheck("a", 100L) }
        assertTrue(throttle.hitAndCheck("a", 100L))
    }

    @Test
    fun oneNoisySourceDoesNotThrottleAnother() {
        val throttle = AuthFailureThrottle(limit = 1, windowMs = 1_000L)
        throttle.hitAndCheck("a", 0L); throttle.hitAndCheck("a", 0L)
        assertFalse(throttle.hitAndCheck("b", 0L))
    }

    @Test
    fun theWindowSlidesSoAQuietSourceIsAllowedAgain() {
        val throttle = AuthFailureThrottle(limit = 2, windowMs = 1_000L)
        repeat(3) { throttle.hitAndCheck("a", 0L) }
        assertFalse(throttle.hitAndCheck("a", 5_000L))
    }
}
