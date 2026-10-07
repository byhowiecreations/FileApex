package com.fileapex.cloud.drive

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DriveRelayCryptoTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("relay-crypto").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun sampleFile(size: Int): File {
        val file = File(dir, "plain-$size.bin")
        file.writeBytes(ByteArray(size) { (it * 31 + 7).toByte() })
        return file
    }

    private fun roundTrip(size: Int) {
        val plain = sampleFile(size)
        val key = DriveRelayCrypto.newKey()
        val sealed = File(dir, "sealed-$size.fxe")
        val opened = File(dir, "opened-$size.bin")
        val encrypted = DriveRelayCrypto.encryptFile(plain, sealed, key)
        val decrypted = DriveRelayCrypto.decryptFile(sealed, opened, key)
        assertEquals(size.toLong(), encrypted.plainSizeBytes)
        assertEquals(encrypted.plainSha256, decrypted.plainSha256)
        assertArrayEquals(plain.readBytes(), opened.readBytes())
    }

    @Test
    fun emptyFileRoundTrips() = roundTrip(0)

    @Test
    fun smallFileRoundTrips() = roundTrip(1234)

    @Test
    fun fileOfExactlyOneChunkRoundTrips() = roundTrip(1 shl 20)

    @Test
    fun multiChunkFileRoundTrips() = roundTrip((2 shl 20) + 4321)

    @Test
    fun ciphertextDiffersFromPlaintext() {
        val plain = sampleFile(5000)
        val sealed = File(dir, "sealed.fxe")
        DriveRelayCrypto.encryptFile(plain, sealed, DriveRelayCrypto.newKey())
        assertNotEquals(plain.readBytes().toList(), sealed.readBytes().toList().takeLast(5000))
    }

    @Test
    fun wrongKeyFails() {
        val plain = sampleFile(5000)
        val sealed = File(dir, "sealed.fxe")
        DriveRelayCrypto.encryptFile(plain, sealed, DriveRelayCrypto.newKey())
        try {
            DriveRelayCrypto.decryptFile(sealed, File(dir, "out.bin"), DriveRelayCrypto.newKey())
            fail("expected a decrypt failure")
        } catch (_: javax.crypto.AEADBadTagException) {
        }
    }

    @Test
    fun flippedByteFails() {
        val plain = sampleFile((2 shl 20) + 100)
        val key = DriveRelayCrypto.newKey()
        val sealed = File(dir, "sealed.fxe")
        DriveRelayCrypto.encryptFile(plain, sealed, key)
        val bytes = sealed.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        sealed.writeBytes(bytes)
        try {
            DriveRelayCrypto.decryptFile(sealed, File(dir, "out.bin"), key)
            fail("expected a decrypt failure")
        } catch (_: javax.crypto.AEADBadTagException) {
        }
    }

    @Test
    fun truncatedFileFails() {
        val plain = sampleFile((3 shl 20) + 100)
        val key = DriveRelayCrypto.newKey()
        val sealed = File(dir, "sealed.fxe")
        DriveRelayCrypto.encryptFile(plain, sealed, key)
        val bytes = sealed.readBytes()
        // Cut exactly at a chunk boundary: the surviving chunks are valid but none is flagged last.
        val boundary = 15 + 2 * ((1 shl 20) + 16)
        sealed.writeBytes(bytes.copyOf(boundary))
        try {
            DriveRelayCrypto.decryptFile(sealed, File(dir, "out.bin"), key)
            fail("expected a decrypt failure")
        } catch (_: javax.crypto.AEADBadTagException) {
        }
    }
}
