package com.fileapex.ui.explorer

import androidx.compose.ui.graphics.ImageBitmap
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteThumbnailCacheBytesTest {
    private fun bitmap() = ImageBitmap(16, 16)
    private val bitmapBytes = 16L * 16L * 4L

    @Test
    fun theOldestThumbnailLeavesWhenTheByteCapIsPassed() {
        val cache = RemoteThumbnailCache(maxEntries = 100, maxBytes = bitmapBytes * 2 + 10)
        cache.put("a", bitmap()); cache.put("b", bitmap()); cache.put("c", bitmap())
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertEquals(bitmapBytes * 2, cache.totalBytes())
    }

    @Test
    fun aReadKeepsAThumbnailAndTheLeastRecentOneLeaves() {
        val cache = RemoteThumbnailCache(maxEntries = 100, maxBytes = bitmapBytes * 2 + 10)
        cache.put("a", bitmap()); cache.put("b", bitmap())
        cache.get("a")
        cache.put("c", bitmap())
        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun storingTheSameKeyAgainDoesNotCountItTwice() {
        val cache = RemoteThumbnailCache(maxEntries = 100, maxBytes = bitmapBytes * 3)
        repeat(5) { cache.put("a", bitmap()) }
        assertEquals(bitmapBytes, cache.totalBytes())
    }

    @Test
    fun aThumbnailLargerThanTheWholeCapIsNotKept() {
        val cache = RemoteThumbnailCache(maxEntries = 100, maxBytes = bitmapBytes - 1)
        cache.put("big", bitmap())
        assertNull(cache.get("big"))
        assertEquals(0L, cache.totalBytes())
    }

    @Test
    fun theEntryCapStillApplies() {
        val cache = RemoteThumbnailCache(maxEntries = 2, maxBytes = bitmapBytes * 100)
        cache.put("a", bitmap()); cache.put("b", bitmap()); cache.put("c", bitmap())
        assertNull(cache.get("a"))
        assertEquals(bitmapBytes * 2, cache.totalBytes())
    }

    @Test
    fun theDefaultCapIsBoundedForPhones() {
        assertTrue(RemoteThumbnailCache.DEFAULT_MAX_BYTES in (8L shl 20)..(32L shl 20))
    }
}
