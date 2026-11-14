package com.fileapex.ui.notes

import com.fileapex.data.note.NoteChecklistItem
import com.fileapex.data.note.NoteRecord
import com.fileapex.presentation.BulletinSearchPolicy
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4BulletinSearchAndPasteTest {
    private fun sampleNote(
        id: String = "n1",
        content: String = "Quarterly review #finance",
        deviceName: String = "Pixel 9",
        attachment: String? = null,
        checklist: List<NoteChecklistItem> = emptyList()
    ) = NoteRecord(
        noteId = id,
        sourceDeviceId = "fx-peer",
        sourceDeviceName = deviceName,
        content = content,
        epochMs = 1_700_000_000_000L,
        attachmentFileName = attachment,
        checklist = checklist
    )

    @Test
    fun searchPolicyMatchesContentAttachmentDeviceAndChecklist() {
        val note = sampleNote(
            content = "Send tax forms #taxes",
            deviceName = "Work Laptop",
            attachment = "Q4-Summary.pdf",
            checklist = listOf(NoteChecklistItem("c1", "Sign invoice", done = false))
        )
        assertTrue(BulletinSearchPolicy.matches(note, ""))
        assertTrue(BulletinSearchPolicy.matches(note, "  TAX  "))
        assertTrue(BulletinSearchPolicy.matches(note, "#taxes"))
        assertTrue(BulletinSearchPolicy.matches(note, "q4-summary"))
        assertTrue(BulletinSearchPolicy.matches(note, "work laptop"))
        assertTrue(BulletinSearchPolicy.matches(note, "sign invoice"))
        assertFalse(BulletinSearchPolicy.matches(note, "grocery list"))
        val second = sampleNote(id = "n2", content = "Grocery list", attachment = null)
        val filtered = BulletinSearchPolicy.filterNotes(
            listOf(note, second),
            com.fileapex.presentation.BulletinFilter.All,
            "summary"
        )
        assertEquals(listOf("n1"), filtered.map { it.noteId })
    }

    @Test
    fun pickedFileFromDroppedPathDecodesFileUriSpaces() {
        val dir = Files.createTempDirectory("fileapex-drop-test").toFile()
        try {
            val file = File(dir, "my photo 01.txt").apply { writeText("hello") }
            val uri = "file://" + file.absolutePath.replace(" ", "%20")
            val picked = pickedFileFromDroppedPath(uri)
            assertNotNull(picked)
            val resolved = picked!!
            assertEquals("my photo 01.txt", resolved.displayName)
            assertEquals(5L, resolved.sizeBytes)
            assertEquals(file.absolutePath, resolved.absolutePath)
            assertNull(pickedFileFromDroppedPath(dir.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun extractClipboardPayloadReadsFilesImagesAndText() {
        val cacheDir = Files.createTempDirectory("fileapex-paste-test").toFile()
        try {
            val sampleFile = File(cacheDir, "clip.txt").apply { writeText("copied") }
            val fileTransferable = object : Transferable {
                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
                    flavor == DataFlavor.javaFileListFlavor

                override fun getTransferData(flavor: DataFlavor): Any = listOf(sampleFile)
            }
            val filePayload = DesktopBulletinClipboard.extractPastePayload(fileTransferable, cacheDir)
            assertTrue(filePayload is BulletinPastePayload.Files)
            val filesPayload = filePayload as BulletinPastePayload.Files
            assertEquals(listOf(sampleFile.absolutePath), filesPayload.paths)

            val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
            val imageTransferable = object : Transferable {
                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
                    flavor == DataFlavor.imageFlavor

                override fun getTransferData(flavor: DataFlavor): Image = image
            }
            val imagePayload = DesktopBulletinClipboard.extractPastePayload(imageTransferable, cacheDir)
            assertTrue(imagePayload is BulletinPastePayload.Files)
            val imageFilesPayload = imagePayload as BulletinPastePayload.Files
            assertEquals(1, imageFilesPayload.paths.size)
            val pastedPng = File(imageFilesPayload.paths.first())
            assertTrue(pastedPng.exists())
            assertTrue(pastedPng.name.endsWith(".png"))

            val textPayload = DesktopBulletinClipboard.extractPastePayload(
                StringSelection("https://example.com"),
                cacheDir
            )
            assertTrue(textPayload is BulletinPastePayload.Text)
            val typedTextPayload = textPayload as BulletinPastePayload.Text
            assertEquals("https://example.com", typedTextPayload.text)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun searchFieldHidesWhenDismissedEvenWithNonBlankQuery() {
        assertTrue(BulletinSearchPolicy.shouldShowSearchField(searchOpen = true))
        assertFalse(BulletinSearchPolicy.shouldShowSearchField(searchOpen = false))
        assertFalse(BulletinSearchPolicy.shouldShowActiveQueryChip(searchOpen = true, query = "invoice"))
        assertFalse(BulletinSearchPolicy.shouldShowActiveQueryChip(searchOpen = false, query = "  "))
        assertTrue(BulletinSearchPolicy.shouldShowActiveQueryChip(searchOpen = false, query = "invoice"))
    }
}
