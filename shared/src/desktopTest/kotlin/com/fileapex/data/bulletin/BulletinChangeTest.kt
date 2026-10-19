package com.fileapex.data.bulletin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BulletinChangeTest {
    private fun message(id: String = "m1", pinned: Boolean = false, content: String = "hi", updatedAt: Long = 0L) = MessageEntity(
        id = id, originDeviceId = "o", senderName = "n", content = content, contentType = 0,
        timestamp = 100L, isPinned = pinned, updatedAt = updatedAt
    )

    @Test
    fun aNewMessageIsStampedWithTheTimeItArrived() {
        val stored = BulletinChange.messageToStore(existing = null, incoming = message(), nowMs = 5_000L)
        assertEquals(5_000L, stored?.updatedAt)
    }

    @Test
    fun theSameMessageArrivingAgainIsNotWrittenSoNoVersionMoves() {
        val existing = message(updatedAt = 1_000L)
        assertNull(BulletinChange.messageToStore(existing, incoming = message(updatedAt = 0L), nowMs = 9_000L))
    }

    @Test
    fun aPinChangeIsStampedAgain() {
        val existing = message(updatedAt = 1_000L)
        val stored = BulletinChange.messageToStore(existing, incoming = message(pinned = true), nowMs = 9_000L)
        assertEquals(9_000L, stored?.updatedAt)
        assertEquals(true, stored?.isPinned)
    }

    @Test
    fun editedContentIsStampedAgain() {
        val existing = message(updatedAt = 1_000L)
        assertEquals(9_000L, BulletinChange.messageToStore(existing, message(content = "edited"), 9_000L)?.updatedAt)
    }

    @Test
    fun aTombstoneWeAlreadyHaveIsNotWrittenAgain() {
        val have = TombstoneEntity(id = "m1", deletedAt = 50L, originDeviceId = "o", updatedAt = 1_000L)
        assertNull(BulletinChange.tombstoneToStore(have, TombstoneEntity("m1", 50L, "o"), 9_000L))
    }

    @Test
    fun aNewTombstoneIsStampedWithTheTimeItArrived() {
        val stored = BulletinChange.tombstoneToStore(null, TombstoneEntity("m1", 50L, "o"), 9_000L)
        assertEquals(9_000L, stored?.updatedAt)
        assertEquals(50L, stored?.deletedAt)
    }
}
