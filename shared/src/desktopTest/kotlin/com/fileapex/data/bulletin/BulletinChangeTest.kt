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

    private val fileJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun fileMessage(localPath: String?, pinned: Boolean = false, updatedAt: Long = 0L, caption: String = "") = MessageEntity(
        id = "f1", originDeviceId = "sig", senderName = "Signature",
        content = (if (caption.isEmpty()) "" else "$caption\n") + fileJson.encodeToString(
            BulletinFileMetadata.serializer(),
            BulletinFileMetadata(fileName = "a.png", sizeBytes = 10L, sha256 = "ab", originNode = "sig", localPath = localPath, previewBase64 = "pv")
        ),
        contentType = BulletinContentType.IMAGE_PREVIEW, timestamp = 100L, isPinned = pinned, updatedAt = updatedAt
    )

    private fun pathOf(message: MessageEntity?): String? =
        message?.let { fileJson.decodeFromString(BulletinFileMetadata.serializer(), it.content.substringAfter("\n{", "{" + it.content.removePrefix("{")).let { json -> if (json.startsWith("{")) json else "{$json" }).localPath }

    @Test
    fun aFileMessageComingBackWithoutAPathKeepsTheStoredPathAndIsNotWritten() {
        val existing = fileMessage(localPath = "/sdcard/a.png", updatedAt = 1_000L)
        assertNull(BulletinChange.messageToStore(existing, fileMessage(localPath = null), nowMs = 9_000L))
    }

    @Test
    fun aChangeToAFileMessageKeepsTheStoredPath() {
        val existing = fileMessage(localPath = "/sdcard/a.png", updatedAt = 1_000L)
        val stored = BulletinChange.messageToStore(existing, fileMessage(localPath = null, pinned = true), nowMs = 9_000L)
        assertEquals(true, stored?.isPinned)
        assertEquals("/sdcard/a.png", pathOf(stored))
    }

    @Test
    fun aCaptionedFileMessageKeepsItsCaptionAndTheStoredPath() {
        val existing = fileMessage(localPath = "/sdcard/a.png", updatedAt = 1_000L, caption = "look")
        val stored = BulletinChange.messageToStore(existing, fileMessage(localPath = null, pinned = true, caption = "look"), nowMs = 9_000L)
        assertEquals("look", stored?.content?.substringBefore("\n{"))
        assertEquals("/sdcard/a.png", pathOf(stored))
    }

    @Test
    fun aFileMessageWeDoNotHaveYetStaysWithoutAPath() {
        val stored = BulletinChange.messageToStore(null, fileMessage(localPath = null), nowMs = 9_000L)
        assertNull(pathOf(stored))
    }
}
