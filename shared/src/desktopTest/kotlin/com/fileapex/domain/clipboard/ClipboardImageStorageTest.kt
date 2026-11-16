package com.fileapex.domain.clipboard

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardImageStorageTest {
    private fun tempDir() = Files.createTempDirectory("clipboard-image-test").toFile().apply { deleteOnExit() }

    @Test
    fun theClipboardCopyIsWrittenWithoutLeavingATemporaryFile() {
        val dir = tempDir()
        val written = ClipboardImageStorage.writeClipboardCopy(dir.path, "image/png", byteArrayOf(1, 2, 3))!!
        assertEquals("clipboard-remote.png", written.name)
        assertEquals(listOf<Byte>(1, 2, 3), written.readBytes().toList())
        assertEquals(listOf("clipboard-remote.png"), dir.list()!!.toList())
    }

    @Test
    fun aNewClipboardCopyReplacesTheLastOneWhateverItsType() {
        val dir = tempDir()
        ClipboardImageStorage.writeClipboardCopy(dir.path, "image/png", byteArrayOf(1))
        val second = ClipboardImageStorage.writeClipboardCopy(dir.path, "image/png", byteArrayOf(9, 9))!!
        assertEquals(listOf<Byte>(9, 9), second.readBytes().toList())
        val jpeg = ClipboardImageStorage.writeClipboardCopy(dir.path, "image/jpeg", byteArrayOf(7))!!
        assertEquals("clipboard-remote.jpg", jpeg.name)
        assertEquals("only the newest copy is kept", listOf("clipboard-remote.jpg"), dir.list()!!.toList())
    }

    @Test
    fun anEmptyImageOrAnUnusableFolderWritesNothing() {
        val dir = tempDir()
        assertNull(ClipboardImageStorage.writeClipboardCopy(dir.path, "image/png", ByteArray(0)))
        assertTrue(dir.list()!!.isEmpty())
        val file = java.io.File(dir, "not-a-folder").apply { writeText("x") }
        assertNull(ClipboardImageStorage.writeClipboardCopy(file.path + "/inside", "image/png", byteArrayOf(1)))
    }

    @Test
    fun aSavedDownloadIsAtomicAndKeepsTheSenderLabelSafe() {
        val dir = tempDir()
        val saved = ClipboardImageStorage.saveToDirectory(byteArrayOf(5, 6), "image/png", "Cliff's/MacBook", dir.path, nowEpochMs = 123L)!!
        assertEquals(dir.canonicalFile, java.io.File(saved).canonicalFile.parentFile)
        assertTrue("no separator from the sender name inside the file name", java.io.File(saved).name.none { it == '/' || it == '\\' })
        assertTrue("no temporary file is left behind", dir.list()!!.none { it.endsWith(".tmp") })
    }
}
