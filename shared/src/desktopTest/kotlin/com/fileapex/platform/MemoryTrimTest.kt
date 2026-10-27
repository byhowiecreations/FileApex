package com.fileapex.platform

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryTrimTest {
    private class Counter : Trimmable {
        var trims = 0
        override fun trimMemory() { trims++ }
    }

    private val reports = mutableListOf<String>()

    @Before
    fun captureReports() {
        MemoryTrim.report = { reports += it }
    }

    @After
    fun restoreReports() {
        MemoryTrim.report = MemoryTrim.defaultReport
    }

    @Test
    fun releaseAsksEveryRegisteredOwnerToTrim() {
        val a = Counter()
        val b = Counter()
        MemoryTrim.register(a)
        MemoryTrim.register(b)
        MemoryTrim.release()
        assertEquals(1, a.trims)
        assertEquals(1, b.trims)
        assertTrue(reports.any { it.startsWith("memory trim: asking") })
    }

    @Test
    fun anOwnerThatThrowsDoesNotStopTheOthersAndIsReported() {
        val bad = object : Trimmable { override fun trimMemory() = throw IllegalStateException("x") }
        val good = Counter()
        MemoryTrim.register(bad)
        MemoryTrim.register(good)
        MemoryTrim.release()
        assertEquals(1, good.trims)
        assertTrue(reports.any { it.startsWith("memory trim failed") })
    }

    @Test
    fun registeringTwiceStillTrimsOnce() {
        val a = Counter()
        MemoryTrim.register(a)
        MemoryTrim.register(a)
        MemoryTrim.release()
        assertEquals(1, a.trims)
    }
}
