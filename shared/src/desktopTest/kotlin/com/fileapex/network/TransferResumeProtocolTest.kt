package com.fileapex.network

import com.fileapex.platform.fileDigest
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TransferResumeProtocolTest {

    @Test
    fun protocolQueryKeysIncludeSha256() {
        assertEquals("sha256", TransferResumeProtocol.SHA256_QUERY)
    }

    @Test
    fun parseByteOffsetPrefersValidQueryOverHeader() {
        val offset = TransferResumeProtocol.parseByteOffset(
            queryOffset = "1024",
            rangeHeader = "bytes=2048-4096"
        )
        assertEquals(1024L, offset)
    }

    @Test
    fun wholeFileSha256DigestVerification() {
        val tempDir = Files.createTempDirectory("sha256_test").toFile()
        try {
            val file = File(tempDir, "sample.dat")
            val content = "FileApex whole-file integrity verification payload".toByteArray(Charsets.UTF_8)
            file.writeBytes(content)

            val expectedDigest = MessageDigest.getInstance("SHA-256")
                .digest(content)
                .joinToString("") { "%02x".format(it) }

            val actualDigest = fileDigest(file.absolutePath, -1L)
            assertNotNull(actualDigest)
            assertEquals(expectedDigest, actualDigest?.lowercase())

            // Corrupt file by appending a byte
            file.appendBytes(byteArrayOf(0x42))
            val corruptedDigest = fileDigest(file.absolutePath, -1L)
            assertNotNull(corruptedDigest)
            assertNotEquals(expectedDigest, corruptedDigest?.lowercase())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
