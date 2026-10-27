package com.fileapex.network

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerBulletinClientTest {
    private val dir: File = Files.createTempDirectory("bulletin-download-test").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun part(content: String): String = File(dir, "photo.png.m1.part").also { it.writeText(content) }.path
    private val sha = { text: String -> com.fileapex.util.sha256HexFile(File(dir, "tmp").also { it.writeText(text) }.path) }

    @Test
    fun retractWithoutExtrasIsJustTheIdAndAction() {
        assertEquals(
            """{"noteId":"n1","action":"RETRACT_MESSAGE"}""",
            PeerBulletinClient.noteDeletePayload("n1", null, "  ", "")
        )
    }

    @Test
    fun extrasAreAppendedInOrderWithQuotesRemoved() {
        assertEquals(
            """{"noteId":"n1","action":"RETRACT_MESSAGE","driveFileId":"d1","checksum":"abc","attachmentName":"a b.txt"}""",
            PeerBulletinClient.noteDeletePayload("n1", "d\"1", "abc", "a \"b.txt")
        )
    }

    @Test
    fun aVerifiedPartIsMovedToItsFinalName() {
        val final = File(dir, "photo.png").path
        val path = PeerBulletinClient.finishDownload(part("pixels"), final, 6L, sha("pixels"))
        assertEquals(final, path)
        assertEquals("pixels", File(path).readText())
        assertFalse(File(dir, "photo.png.m1.part").exists())
    }

    @Test
    fun anExistingFileIsNeverOverwritten() {
        val final = File(dir, "photo.png").also { it.writeText("old") }.path
        val path = PeerBulletinClient.finishDownload(part("new"), final, 3L, sha("new"))
        assertEquals("old", File(final).readText())
        assertEquals("new", File(path).readText())
        assertTrue(path != final)
    }

    @Test
    fun aWrongSizeDeletesThePartAndFails() {
        val partPath = part("pixels")
        assertThrows(IllegalStateException::class.java) {
            PeerBulletinClient.finishDownload(partPath, File(dir, "photo.png").path, 99L, "")
        }
        assertFalse(File(partPath).exists())
        assertFalse(File(dir, "photo.png").exists())
    }

    @Test
    fun aWrongChecksumDeletesThePartAndFails() {
        val partPath = part("pixels")
        assertThrows(IllegalStateException::class.java) {
            PeerBulletinClient.finishDownload(partPath, File(dir, "photo.png").path, 6L, "00".repeat(32))
        }
        assertFalse(File(partPath).exists())
        assertFalse(File(dir, "photo.png").exists())
    }

    @Test
    fun noExpectedSizeOrChecksumAcceptsThePart() {
        val path = PeerBulletinClient.finishDownload(part("x"), File(dir, "photo.png").path, 0L, "")
        assertEquals("x", File(path).readText())
    }
}
