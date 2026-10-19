package com.fileapex.domain.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovalCatchUpTest {
    private val now = 1_792_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun removal(id: String, stamp: Long, protocol: Int = 2) =
        RemovedDeviceRecord(deviceId = id, clusterVersion = stamp, membershipProtocol = protocol)

    @Test
    fun matchesTheGoDaemonForTheSameRecords() {
        // The same two records and expected value are in the Go test TestRemovalFingerprintMatchesKotlin.
        val records = listOf(removal("bbbb-2", 1_760_000_005_000L), removal("aaaa-1", 1_760_000_000_000L))
        assertEquals("9bc86a65f2da0fbb", RemovalFingerprint.of(records))
    }

    @Test
    fun noRemovalsReadsZero() {
        assertEquals("0", RemovalFingerprint.of(emptyList()))
    }

    @Test
    fun orderDoesNotMatterButAStampDoes() {
        val a = removal("x-1", now - day)
        val b = removal("y-2", now - 2 * day)
        assertEquals(RemovalFingerprint.of(listOf(a, b)), RemovalFingerprint.of(listOf(b, a)))
        assertNotEquals(RemovalFingerprint.of(listOf(a)), RemovalFingerprint.of(listOf(a.copy(clusterVersion = a.clusterVersion + 1))))
    }

    @Test
    fun onlyRecentCurrentProtocolRecordsCount() {
        val recent = removal("recent-1", now - 2 * day)
        val ancient = removal("ancient-2", now - 90 * day)
        val legacy = removal("legacy-3", now - 2 * day, protocol = 0)
        val kept = RemovalFingerprint.windowed(listOf(recent, ancient, legacy), now)
        assertEquals(listOf("recent-1"), kept.map { it.deviceId })
    }

    @Test
    fun theWindowEdgeIsWholeUtcDaysSoTwoClocksAgree() {
        val a = RemovalFingerprint.cutoff(now)
        val b = RemovalFingerprint.cutoff(now + 3_600_000L)
        assertEquals(a, b)
        assertEquals(0L, a % day)
    }

    @Test
    fun aPeerIsReadOncePerDistinctFingerprint() {
        val ledger = RemovalPullLedger()
        assertTrue(ledger.shouldPull("omv", theirs = "aaaa", ours = "bbbb"))
        assertFalse(ledger.shouldPull("omv", theirs = "aaaa", ours = "bbbb"))
        assertFalse(ledger.shouldPull("omv", theirs = "aaaa", ours = "bbbb"))
        assertTrue(ledger.shouldPull("omv", theirs = "cccc", ours = "bbbb"))
        assertTrue(ledger.shouldPull("unraid", theirs = "aaaa", ours = "bbbb"))
    }

    @Test
    fun equalFingerprintsNeverPullAndResetTheMemory() {
        val ledger = RemovalPullLedger()
        assertTrue(ledger.shouldPull("omv", "aaaa", "bbbb"))
        assertFalse(ledger.shouldPull("omv", "bbbb", "bbbb"))
        assertTrue(ledger.shouldPull("omv", "aaaa", "bbbb"))
    }

    @Test
    fun aFailedReadMayBeTriedAgain() {
        val ledger = RemovalPullLedger()
        assertTrue(ledger.shouldPull("omv", "aaaa", "bbbb"))
        ledger.forget("omv")
        assertTrue(ledger.shouldPull("omv", "aaaa", "bbbb"))
    }

    @Test
    fun aPeerWithoutAFingerprintIsNeverRead() {
        val ledger = RemovalPullLedger()
        assertFalse(ledger.shouldPull("old-build", theirs = "", ours = "bbbb"))
    }
}
