package com.fileapex.security.tls

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.clipboard.ClipboardSenderCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClipboardSenderCheckTest {
    private fun device(key: String = "real-key", removed: Boolean = false) = PairedDeviceEntity(
        deviceId = "peer", deviceName = "Peer", lastKnownIp = "192.168.1.5", port = 8080,
        publicKeyHash = "h", publicKey = key, rootPath = "/", isRemoved = removed
    )

    private fun rejected(block: () -> Unit): String =
        try { block(); fail("expected rejection"); "" } catch (e: IllegalStateException) { e.message.orEmpty() }

    @Test
    fun recordedKeyIsAccepted() {
        assertEquals("real-key", ClipboardSenderCheck.verify(device(), "", " real-key "))
    }

    @Test
    fun unknownOrRemovedSenderIsRejected() {
        assertEquals("clipboard_sender_unknown", rejected { ClipboardSenderCheck.verify(null, "", "real-key") })
        assertEquals("clipboard_sender_unknown", rejected { ClipboardSenderCheck.verify(device(removed = true), "", "real-key") })
    }

    @Test
    fun forgedKeyForARealDeviceIsRejected() {
        assertEquals("clipboard_sender_unverified", rejected { ClipboardSenderCheck.verify(device(), "", "attacker-key") })
        assertEquals("clipboard_sender_unverified", rejected { ClipboardSenderCheck.verify(device(), "", "") })
    }

    @Test
    fun noRecordedKeyMeansNothingCanBeVerified() {
        assertEquals("clipboard_sender_unverified", rejected { ClipboardSenderCheck.verify(device(key = ""), "", "anything") })
    }

    @Test
    fun cloudRecordIsUsedOnlyWhenNoKeyIsStored() {
        assertEquals("cloud-key", ClipboardSenderCheck.verify(device(key = ""), "cloud-key", "cloud-key"))
        assertTrue(rejected { ClipboardSenderCheck.verify(device(), "cloud-key", "cloud-key") }.isNotEmpty())
    }
}
