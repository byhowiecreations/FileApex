package com.fileapex.notice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeSettingsStoreTest {
    private val kv = MapKvStore()
    private val store = NoticeSettingsStore(kv)

    @Test
    fun defaultsAreOnAndUnexplained() {
        assertTrue(store.enabled.value)
        assertFalse(store.choiceMade.value)
        assertEquals(0L, store.highestSerial())
        assertNull(store.etag())
        assertNull(store.cachedFile())
    }

    @Test
    fun aFileLargerThanOneBackendValueSurvivesAsChunks() {
        val file = ("x".repeat(100) + "ñ更").repeat(250).encodeToByteArray()
        store.saveAccepted(file, 9, "\"etag\"", emptySet())
        assertArrayEquals(file, NoticeSettingsStore(kv).cachedFile())
        assertEquals(9L, NoticeSettingsStore(kv).highestSerial())
        assertEquals("\"etag\"", NoticeSettingsStore(kv).etag())
    }

    @Test
    fun aShorterFileLeavesNoStaleChunks() {
        store.saveAccepted("a".repeat(20_000).encodeToByteArray(), 1, null, emptySet())
        store.saveAccepted("short".encodeToByteArray(), 2, null, emptySet())
        assertEquals("short", store.cachedFile()!!.decodeToString())
        assertEquals(1, kv.keys("notice_cache_").count { it != "notice_cache_chunks" })
    }

    @Test
    fun aMissingChunkMeansNoCachedFile() {
        store.saveAccepted("a".repeat(20_000).encodeToByteArray(), 1, null, emptySet())
        kv.remove("notice_cache_1")
        assertNull(store.cachedFile())
    }

    @Test
    fun dismissedAndSeenAreKeptOnlyForNoticesStillInTheFile() {
        store.dismiss("keep", 100L, forgetSeen = false)
        store.dismiss("gone", 200L, forgetSeen = false)
        store.markSeen(listOf("keep", "gone"))
        store.saveAccepted("{}".encodeToByteArray(), 3, null, setOf("keep"))
        assertEquals(mapOf("keep" to 100L), store.dismissed())
        assertEquals(setOf("keep"), store.seen())
    }

    @Test
    fun dismissingAnImportantNoticeMakesItUnreadAgain() {
        store.markSeen(listOf("a", "b"))
        store.dismiss("a", 1L, forgetSeen = true)
        assertEquals(setOf("b"), store.seen())
    }

    @Test
    fun revisionChangesWhenWhatIsShownChanges() {
        val start = store.revision.value
        store.markSeen(listOf("a"))
        store.markSeen(listOf("a"))
        assertEquals(start + 1, store.revision.value)
        store.dismiss("a", 1L, false)
        assertEquals(start + 2, store.revision.value)
    }

    @Test
    fun wipeDataClearsTheChoiceAndEveryNoticeState() {
        store.markChoiceMade()
        store.setEnabled(false)
        store.saveAccepted("file".encodeToByteArray(), 4, "e", setOf("a"))
        store.dismiss("a", 5L, false)
        store.markSeen(listOf("a"))
        store.setLastSuccessMs(7L)
        kv.clear()
        store.reload()
        assertFalse(store.choiceMade.value)
        assertTrue(store.enabled.value)
        assertEquals(0L, store.highestSerial())
        assertNull(store.etag())
        assertNull(store.cachedFile())
        assertTrue(store.dismissed().isEmpty())
        assertTrue(store.seen().isEmpty())
        assertEquals(0L, store.lastSuccessMs())
    }

    @Test
    fun theChoiceAndSettingSurviveARestart() {
        store.markChoiceMade()
        store.setEnabled(false)
        val again = NoticeSettingsStore(kv)
        assertTrue(again.choiceMade.value)
        assertFalse(again.enabled.value)
    }
}
