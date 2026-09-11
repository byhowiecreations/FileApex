package com.fileapex.data.bulletin

import com.fileapex.platform.fastScanDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BulletinLockAndSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun fastScanDirectoryCorrectlyClassifiesFilesAndFolders() {
        val root = tempFolder.newFolder("scan_root")
        val subDir = File(root, "SubFolder").apply { mkdirs() }
        val testFile = File(root, "document.pdf").apply { writeText("Sample content") }
        val testImg = File(root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val hiddenFile = File(root, ".hidden").apply { writeText("hidden") }

        val (directories, files) = fastScanDirectory(root.absolutePath)

        assertEquals(1, directories.size)
        assertEquals("SubFolder", directories[0].name)
        assertTrue(directories[0].isDirectory)
        assertEquals("inode/directory", directories[0].mimeType)

        assertEquals(2, files.size)
        val fileNames = files.map { it.name }
        assertTrue("document.pdf in files", fileNames.contains("document.pdf"))
        assertTrue("photo.jpg in files", fileNames.contains("photo.jpg"))
        assertFalse("hidden file omitted", fileNames.contains(".hidden"))

        val doc = files.first { it.name == "document.pdf" }
        assertFalse("file isDirectory must be false", doc.isDirectory)
        assertEquals("application/pdf", doc.mimeType)
        assertTrue("file size must be greater than 0", doc.sizeBytes > 0)
    }

    @Test
    fun lockedNoteAttributesPreserved() {
        val msg = MessageEntity(
            id = "msg-123",
            originDeviceId = "dev-1",
            senderName = "Peer",
            content = "Locked note",
            contentType = BulletinContentType.TEXT,
            timestamp = 1000L,
            isDeleted = false,
            isPinned = true
        )
        val note = msg.toNoteRecord()
        assertTrue("note attachmentPinned must be true", note.attachmentPinned)
    }
}
