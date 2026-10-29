package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.util.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ClusterDigestTest {
    private fun device(id: String, stamp: Long, removed: Boolean = false) = PairedDeviceEntity(
        deviceId = id, deviceName = id, lastKnownIp = "10.0.0.1", port = 8080, publicKeyHash = "h$id", publicKey = "k$id",
        rootPath = "/", clusterVersion = stamp, isRemoved = removed
    )

    @Test
    fun theDigestIsTheFirstSixteenHexOfTheSortedLines() {
        val text = "a:5:a\nb:7:r\nself:9:a\n"
        val expected = sha256Hex(text.encodeToByteArray()).take(16)
        val rows = listOf(
            ClusterDigest.Row("self", 9L, ClusterDigest.ACTIVE),
            ClusterDigest.Row("b", 7L, ClusterDigest.REMOVED),
            ClusterDigest.Row("a", 5L, ClusterDigest.ACTIVE)
        )
        assertEquals(expected, ClusterDigest.of(rows))
    }

    @Test
    fun theOrderOfTheRowsDoesNotMatter() {
        val one = ClusterDigest.Row("a", 1L, ClusterDigest.ACTIVE)
        val two = ClusterDigest.Row("b", 2L, ClusterDigest.REMOVED)
        assertEquals(ClusterDigest.of(listOf(one, two)), ClusterDigest.of(listOf(two, one)))
    }

    @Test
    fun aStampOrAStateChangeMovesTheDigest() {
        val base = ClusterDigest.of(listOf(ClusterDigest.Row("a", 1L, ClusterDigest.ACTIVE)))
        assertNotEquals(base, ClusterDigest.of(listOf(ClusterDigest.Row("a", 2L, ClusterDigest.ACTIVE))))
        assertNotEquals(base, ClusterDigest.of(listOf(ClusterDigest.Row("a", 1L, ClusterDigest.REMOVED))))
    }

    @Test
    fun nothingGivesTheNoneValue() {
        assertEquals(ClusterDigest.NONE, ClusterDigest.of(emptyList()))
    }

    @Test
    fun rowsHoldThisDeviceTheActiveRosterAndTheWindowedRemovals() {
        val removal = RemovedDeviceRecord(deviceId = "gone", clusterVersion = 4L, removedAt = 4L, membershipProtocol = 2)
        val rows = ClusterDigest.rows(
            selfId = "me", selfStamp = 8L,
            roster = listOf(device("a", 5L), device("b", 6L, removed = true), device("me", 1L)),
            removals = listOf(removal)
        )
        assertEquals(
            setOf(
                ClusterDigest.Row("me", 8L, ClusterDigest.ACTIVE),
                ClusterDigest.Row("a", 5L, ClusterDigest.ACTIVE),
                ClusterDigest.Row("gone", removal.membershipVersion(), ClusterDigest.REMOVED)
            ),
            rows.toSet()
        )
    }
}
