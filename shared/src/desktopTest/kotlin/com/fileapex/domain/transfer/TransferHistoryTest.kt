package com.fileapex.domain.transfer

import com.fileapex.util.TimeUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransferHistoryTest {
    @Before @After fun clean() { TransferHistory.clear(); TransferActivityGuard.reset() }

    private fun entry(i: Int, at: Long) = TransferHistoryEntry("h$i", "f$i", "Mac", 100L + i, at)

    @Test
    fun `newest first and at most 20 entries`() {
        for (i in 1..25) TransferHistory.record(entry(i, at = 1_000L + i), nowMs = 1_000L + i)
        val shown = TransferHistory.visible(nowMs = 2_000L)
        assertEquals(20, shown.size)
        assertEquals("h25", shown.first().id)
        assertEquals("h6", shown.last().id)
    }

    @Test
    fun `an entry is dropped after 24 hours`() {
        val day = 24L * 60 * 60 * 1000
        TransferHistory.record(entry(1, at = 0L), nowMs = 0L)
        TransferHistory.record(entry(2, at = day), nowMs = day)
        assertEquals(listOf("h2", "h1"), TransferHistory.visible(nowMs = day).map { it.id })
        assertEquals(listOf("h2"), TransferHistory.visible(nowMs = day + 1).map { it.id })
    }

    @Test
    fun `a finished send is recorded when its stream ends and an unfinished one is not`() {
        TransferActivityGuard.beginTransfer(fileName = "done.bin", destinationDeviceName = "Mac", deviceId = "d1")
        TransferActivityGuard.updateProgress(100, 100, deviceId = "d1")
        TransferActivityGuard.endTransfer(deviceId = "d1")
        TransferActivityGuard.beginTransfer(fileName = "cut.bin", destinationDeviceName = "Fold", deviceId = "d2")
        TransferActivityGuard.updateProgress(40, 100, deviceId = "d2")
        TransferActivityGuard.endTransfer(deviceId = "d2")
        val shown = TransferHistory.visible(TimeUtils.now())
        assertEquals(listOf("done.bin"), shown.map { it.fileName })
        assertEquals("Mac", shown.single().deviceName)
        assertEquals(100L, shown.single().bytes)
        assertTrue(shown.single().finishedAtMs > 0L)
    }
}
