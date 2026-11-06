package com.fileapex.domain.clipboard

import com.fileapex.util.TimeUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClipboardPushDeduperLockedReadTest {
    private var reads = 0
    private var stored: PersistedDeduperState = PersistedDeduperState(null, emptyMap())
    private var readable = false
    private val saves = mutableListOf<Pair<String?, Map<String, Long>>>()

    @Before
    fun setUp() {
        ClipboardPushDeduper.resetForTest(
            loader = { reads++; if (readable) stored else null },
            saver = { last, seen -> saves += last to seen.toMap(); stored = PersistedDeduperState(last, seen.toMap()) }
        )
    }

    @After
    fun tearDown() = ClipboardPushDeduper.resetForTest(
        loader = { PersistedDeduperState(null, emptyMap()) },
        saver = { _, _ -> }
    )

    private fun hash(text: String) = ClipboardPushDeduper.hashOf(text)

    @Test
    fun aLockedReadIsRetriedOnEveryCallUntilItOpens() {
        repeat(3) { ClipboardPushDeduper.isDuplicate("one") }
        assertEquals(3, reads)
        readable = true
        ClipboardPushDeduper.isDuplicate("one")
        ClipboardPushDeduper.isDuplicate("one")
        assertEquals(4, reads)
    }

    @Test
    fun whatIsRememberedWhileLockedIsKnownAtOnceButNotWritten() {
        ClipboardPushDeduper.remember("fresh")
        assertTrue(ClipboardPushDeduper.isDuplicate("fresh"))
        assertTrue(saves.isEmpty())
    }

    @Test
    fun whenTheKeyOpensTheStoredHistoryIsKeptAndMergedWithWhatWasRememberedMeanwhile() {
        val now = TimeUtils.now()
        stored = PersistedDeduperState(hash("old"), mapOf(hash("old") to now - 1000))
        ClipboardPushDeduper.remember("fresh")
        readable = true
        assertTrue(ClipboardPushDeduper.isDuplicate("old"))
        assertTrue(ClipboardPushDeduper.isDuplicate("fresh"))
        assertEquals(setOf(hash("old"), hash("fresh")), stored.seenHashes.keys)
        assertEquals(1, saves.size)
    }

    @Test
    fun aTextForgottenWhileLockedStaysForgottenAfterTheStoredHistoryOpens() {
        val now = TimeUtils.now()
        stored = PersistedDeduperState(null, mapOf(hash("gone") to now - 1000))
        ClipboardPushDeduper.forget("gone")
        readable = true
        assertFalse(ClipboardPushDeduper.isDuplicate("gone"))
    }

    @Test
    fun onceReadableTheStateIsKeptAndNotReadAgain() {
        readable = true
        repeat(4) { ClipboardPushDeduper.isDuplicate("x") }
        assertEquals(1, reads)
    }
}
