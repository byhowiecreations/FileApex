package com.fileapex.cloud

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotePushCryptoTest {
    // A stand-in cipher that hides the text from a plain search, so the tests need no keys.
    private val encrypt: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it.reversedArray()) }
    private val decrypt: (String) -> ByteArray = { Base64.getDecoder().decode(it).reversedArray() }

    private val fields = SealedNoteFields(
        content = "meet me at the lake house",
        driveFileId = "drive-123",
        checksum = "abc",
        attachmentName = "tax-return-2025.pdf",
        attachmentSize = 4096L
    )

    @Test
    fun theFieldsComeBackExactlyAfterSealingAndOpening() {
        val sealed = NotePushCrypto.sealFields(fields, encrypt)
        assertEquals(fields, NotePushCrypto.openFields(sealed, decrypt))
    }

    @Test
    fun theMergedDataHasTheFieldsBackUnderTheNamesTheHandlersRead() {
        val data = mapOf(
            FcmWakeProtocol.KEY_TYPE to FcmWakeProtocol.TYPE_NOTE_SYNC,
            FcmWakeProtocol.KEY_SOURCE_DEVICE_ID to "sender",
            FcmWakeProtocol.KEY_NOTE_ID to "note-1",
            FcmWakeProtocol.KEY_SEALED to "ciphertext"
        )
        val merged = NotePushCrypto.merge(data, fields)
        assertEquals("meet me at the lake house", merged[FcmWakeProtocol.Keys.CONTENT])
        assertEquals("drive-123", merged[FcmWakeProtocol.Keys.DRIVE_FILE_ID])
        assertEquals("abc", merged[FcmWakeProtocol.Keys.CHECKSUM])
        assertEquals("tax-return-2025.pdf", merged[FcmWakeProtocol.Keys.ATTACHMENT_NAME])
        assertEquals("4096", merged[FcmWakeProtocol.Keys.ATTACHMENT_SIZE])
        assertEquals("note-1", merged[FcmWakeProtocol.KEY_NOTE_ID])
        assertNull(merged[FcmWakeProtocol.KEY_SEALED])
    }

    @Test
    fun theCiphertextAloneDoesNotShowTheContentOrTheFileName() {
        val sealed = NotePushCrypto.sealFields(fields, encrypt)
        assertFalse(sealed.contains("lake house"))
        assertFalse(sealed.contains("tax-return"))
        assertFalse(sealed.contains("drive-123"))
    }

    @Test
    fun emptyFieldsAddNothingToTheData() {
        val merged = NotePushCrypto.merge(mapOf("a" to "b"), SealedNoteFields())
        assertEquals(mapOf("a" to "b"), merged)
        assertTrue(NotePushCrypto.MAX_SEALED_INLINE_BYTES < 3000)
    }
}
