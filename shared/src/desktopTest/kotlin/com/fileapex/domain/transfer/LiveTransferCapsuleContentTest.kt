package com.fileapex.domain.transfer

import com.fileapex.i18n.AppI18n
import com.fileapex.platform.formatHostFileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTransferCapsuleContentTest {
    private fun stats(
        file: String = "",
        sent: Long = 0L,
        total: Long = 0L,
        progress: Float = 0f,
        speed: String = "",
        eta: String = "",
        receiving: Boolean = false
    ) = LiveTransferStats(
        isActive = true,
        currentFileName = file,
        sentBytes = sent,
        totalBytes = total,
        progress = progress,
        speedFormatted = speed,
        etaFormatted = eta,
        isReceiving = receiving
    )

    @Test
    fun `active receive shows filename percent bytes speed and eta`() {
        val sent = 400_000_000L
        val total = 1_000_000_000L
        val content = liveTransferCapsuleContent(
            stats = stats(
                file = "video.mp4",
                sent = sent,
                total = total,
                progress = 0.4f,
                speed = "42.5 MB/s",
                eta = "14s left",
                receiving = true
            ),
            queueCount = 0
        )
        assertEquals("video.mp4", content.title)
        assertTrue(content.isReceiving)
        assertFalse(content.indeterminate)
        assertEquals("40%", content.percentLabel)
        assertEquals(0.4f, content.progress, 0.0001f)
        val expectedBytes = "${formatHostFileSize(sent)} / ${formatHostFileSize(total)}"
        assertEquals("$expectedBytes • 42.5 MB/s • 14s left", content.subtitle)
    }

    @Test
    fun `blank filename falls back to sending or receiving label`() {
        val singleSend = liveTransferCapsuleContent(stats(receiving = false), queueCount = 0, activeSendingSummary = "a.jpg")
        assertEquals(AppI18n.t("sending_file"), singleSend.title)
        assertFalse(singleSend.isReceiving)

        val multiSend = liveTransferCapsuleContent(stats(receiving = false), queueCount = 2, activeSendingSummary = "a.jpg, b.jpg")
        assertEquals(AppI18n.t("sending_files"), multiSend.title)

        val receive = liveTransferCapsuleContent(stats(receiving = true), queueCount = 0)
        assertEquals(AppI18n.t("receiving_file"), receive.title)
        assertTrue(receive.isReceiving)
    }

    @Test
    fun `unknown total is indeterminate with empty percent label and fallback subtitle`() {
        val bare = liveTransferCapsuleContent(stats(file = "archive.zip", receiving = true), queueCount = 0)
        assertTrue(bare.indeterminate)
        assertEquals("", bare.percentLabel)
        assertEquals(AppI18n.t("active_transfer"), bare.subtitle)

        val queued = liveTransferCapsuleContent(stats(file = "archive.zip"), queueCount = 3)
        assertTrue(queued.indeterminate)
        assertEquals(AppI18n.plural("items_in_queue", 3, "3"), queued.subtitle)
    }

    @Test
    fun `queue count appends cleanly when metrics exist`() {
        val content = liveTransferCapsuleContent(
            stats = stats(file = "a.bin", sent = 500L, total = 1000L, progress = 0.5f, speed = "1 MB/s"),
            queueCount = 3
        )
        assertEquals("${formatHostFileSize(500L)} / ${formatHostFileSize(1000L)} • 1 MB/s (3)", content.subtitle)
    }
}
