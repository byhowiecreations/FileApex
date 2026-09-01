package com.fileapex.domain.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClipboardPushDeduperTest {

    @Before
    fun resetMemory() {
        ClipboardPushDeduper.clearSession()
    }

    @Test
    fun hashIsStableAndDoesNotContainPlaintext() {
        val text = "secret clipboard payload"
        val hash = ClipboardPushDeduper.hashOf(text)
        assertEquals(64, hash.length)
        assertFalse(hash.contains("secret", ignoreCase = true))
        assertEquals(hash, ClipboardPushDeduper.hashOf("  secret clipboard payload  "))
        assertNotEquals(hash, ClipboardPushDeduper.hashOf("other text"))
    }

    @Test
    fun manualPushAllowsFirstCopyThenBlocksSameHash() {
        val text = "once only"
        assertTrue(ClipboardPushDeduper.shouldAllowManualPush(text))
        ClipboardPushDeduper.remember(text)
        assertTrue(ClipboardPushDeduper.isDuplicate(text))
        assertFalse(ClipboardPushDeduper.shouldAllowManualPush(text))
        assertTrue(ClipboardPushDeduper.shouldAllowManualPush("new copy"))
    }

    @Test
    fun initializationBlocksAutomaticPushButNotManual() {
        ClipboardPushDeduper.beginInitialization()
        assertTrue(ClipboardPushDeduper.isInitializing)
        assertFalse(ClipboardPushDeduper.shouldAllowAutomaticPush("stale clip from last session"))
        assertTrue(ClipboardPushDeduper.shouldAllowManualPush("stale clip from last session"))
        ClipboardPushDeduper.endInitialization()
        assertTrue(ClipboardPushDeduper.shouldAllowAutomaticPush("stale clip from last session"))
        ClipboardPushDeduper.remember("stale clip from last session")
        assertFalse(ClipboardPushDeduper.shouldAllowAutomaticPush("stale clip from last session"))
    }

    @Test
    fun inboundRememberPreventsEchoPush() {
        ClipboardPushDeduper.remember("from mac")
        assertFalse(ClipboardPushDeduper.shouldAllowManualPush("from mac"))
        assertFalse(ClipboardPushDeduper.shouldAllowAutomaticPush("from mac"))
    }

    @Test
    fun staleClipTimestampIsRejectedFromAutomaticPush() {
        val oldTimestamp = System.currentTimeMillis() - 300_000L // 5 minutes old
        val text = "copied long ago"
        // Fresh clip allowed
        assertTrue(ClipboardPushDeduper.shouldAllowAutomaticPush("fresh clip", System.currentTimeMillis()))
        // Stale clip rejected and remembered
        assertFalse(ClipboardPushDeduper.shouldAllowAutomaticPush(text, oldTimestamp))
        // Subsequent checks also blocked because it was remembered
        assertFalse(ClipboardPushDeduper.shouldAllowAutomaticPush(text))
    }
}
