package com.fileapex.cloud.drive

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plaintext chunk buffers are zeroed once a file is done; the key stays the caller's to wipe. */
class DriveRelayCryptoWipeTest {
    @Test
    fun theChunkBuffersAreWipedAfterEncryptAndDecrypt() {
        val dir = java.nio.file.Files.createTempDirectory("relay-wipe").toFile()
        try {
            val plain = File(dir, "p").also { it.writeBytes(ByteArray(3000) { i -> (i % 251).toByte() }) }
            val sealed = File(dir, "s")
            val back = File(dir, "b")
            val key = DriveRelayCrypto.newKey()
            val captured = mutableListOf<ByteArray>()
            DriveRelayCrypto.bufferSink = { captured += it }
            DriveRelayCrypto.encryptFile(plain, sealed, key)
            DriveRelayCrypto.decryptFile(sealed, back, key)
            DriveRelayCrypto.bufferSink = null
            assertArrayEquals(plain.readBytes(), back.readBytes())
            assertTrue("buffers were seen", captured.isNotEmpty())
            assertTrue("every buffer is zero", captured.all { buf -> buf.all { it == 0.toByte() } })
        } finally {
            DriveRelayCrypto.bufferSink = null
            dir.deleteRecursively()
        }
    }
}
