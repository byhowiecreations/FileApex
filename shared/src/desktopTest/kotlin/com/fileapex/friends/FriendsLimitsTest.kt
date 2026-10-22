package com.fileapex.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendsLimitsTest {
    @Test
    fun choicesAreTheFiveTheUserPicksFrom() {
        assertEquals(listOf(10, 25, 100, 250, 0), FriendsLimits.choicesMb)
    }

    @Test
    fun zeroMeansNoLimitAndTheRestAreMegabytes() {
        assertEquals(10L * 1024 * 1024, FriendsLimits.maxBytes(10))
        assertEquals(250L * 1024 * 1024, FriendsLimits.maxBytes(250))
        assertTrue(FriendsLimits.maxBytes(0) == Long.MAX_VALUE)
    }
}
