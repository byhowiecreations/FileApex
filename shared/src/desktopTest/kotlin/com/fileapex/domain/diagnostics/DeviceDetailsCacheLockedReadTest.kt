package com.fileapex.domain.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceDetailsCacheLockedReadTest {
    private var reads = 0
    private var disk = ""
    private var readable = false
    private val writes = mutableListOf<String>()

    private fun snap(at: Long) = PeerDeviceDiagnostics(collectedAtEpochMs = at, platform = "Linux")
    private fun raw(vararg ids: String) =
        DeviceDetailsCache.encode(ids.map { CachedDeviceDetails(it, snap(1)) })

    @Before
    fun setUp() {
        DeviceDetailsCache.resetForTest(
            reader = { reads++; if (readable) disk else null },
            writer = { writes += it; disk = it }
        )
    }

    @After
    fun tearDown() = DeviceDetailsCache.resetForTest(reader = { "" }, writer = {})

    @Test
    fun aLockedReadIsRetriedOnEveryCallUntilItOpens() {
        repeat(3) { DeviceDetailsCache.get("a") }
        assertEquals(3, reads)
        readable = true
        DeviceDetailsCache.get("a")
        DeviceDetailsCache.get("a")
        assertEquals(4, reads)
    }

    @Test
    fun anAnswerRememberedWhileLockedIsShownButNeverWritten() {
        DeviceDetailsCache.remember("b", snap(5))
        assertEquals(5L, DeviceDetailsCache.get("b")?.collectedAtEpochMs)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun whenTheKeyOpensTheStoredEntriesAreKeptAndTheHeldAnswerIsMergedAndWritten() {
        disk = raw("a")
        DeviceDetailsCache.remember("b", snap(5))
        readable = true
        assertEquals(1L, DeviceDetailsCache.get("a")?.collectedAtEpochMs)
        assertEquals(5L, DeviceDetailsCache.get("b")?.collectedAtEpochMs)
        assertEquals(setOf("a", "b"), DeviceDetailsCache.decode(disk).map { it.deviceId }.toSet())
        assertEquals(1, writes.size)
    }

    @Test
    fun aHeldAnswerWinsOverAStoredEntryOfTheSameDevice() {
        disk = raw("a")
        DeviceDetailsCache.remember("a", snap(9))
        readable = true
        assertEquals(9L, DeviceDetailsCache.get("a")?.collectedAtEpochMs)
    }

    @Test
    fun nothingStoredAndNothingHeldGivesNoEntry() {
        readable = true
        assertNull(DeviceDetailsCache.get("zzz"))
    }

    @Test
    fun onceReadableTheEntriesAreKeptAndNotReadAgain() {
        readable = true
        repeat(4) { DeviceDetailsCache.get("a") }
        assertEquals(1, reads)
    }
}
