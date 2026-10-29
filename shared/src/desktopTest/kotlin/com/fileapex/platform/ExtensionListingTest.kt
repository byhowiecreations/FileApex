package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** pluginkit takes a moment to list an extension after `-a` returns, so the check waits instead of asking once. */
class ExtensionListingTest {
    @Test
    fun anExtensionThatIsListedAtOnceNeedsNoWait() {
        val sleeps = mutableListOf<Long>()
        assertTrue(ExtensionListing.awaitListed(isListed = { true }, attempts = 5, delayMs = 500, sleep = { sleeps += it }))
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun anExtensionThatShowsUpLaterIsFoundOnALaterLook() {
        var looks = 0
        val sleeps = mutableListOf<Long>()
        assertTrue(ExtensionListing.awaitListed(isListed = { ++looks == 3 }, attempts = 5, delayMs = 500, sleep = { sleeps += it }))
        assertEquals(3, looks)
        assertEquals(listOf(500L, 500L), sleeps)
    }

    @Test
    fun anExtensionThatNeverShowsUpIsReportedAfterTheLastLook() {
        var looks = 0
        val sleeps = mutableListOf<Long>()
        assertFalse(ExtensionListing.awaitListed(isListed = { looks++; false }, attempts = 4, delayMs = 250, sleep = { sleeps += it }))
        assertEquals(4, looks)
        assertEquals(3, sleeps.size)
    }
}
