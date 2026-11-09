package com.fileapex.platform

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test

/** Draws the Windows Drop Files panel empty, dragged over, with files, and sending. Pictures go to the system temp folder for a look. */
class DesktopWindowsDropBoxRenderTest {
    private val entries = listOf(
        DesktopStagedEntry("/a/Holiday photos", "Holiday photos", true),
        DesktopStagedEntry("/a/Quarterly report final version 2.pdf", "Quarterly report final version 2.pdf", false),
        DesktopStagedEntry("/a/notes.txt", "notes.txt", false)
    )

    private fun render(name: String, staged: List<DesktopStagedEntry>, dragOver: Boolean = false, sending: Boolean = false, pinned: Boolean = false): Int {
        val scene = ImageComposeScene(width = 760, height = 840, density = Density(2f)) {
            DropBoxContent(
                stagedEntries = staged,
                isSending = sending,
                isPinned = pinned,
                isDragOver = dragOver,
                destinationText = "Ursula-Ubuntu",
                onTogglePin = {},
                onDragOverChange = {},
                onFilesDropped = {},
                onRemovePath = {},
                onCancel = {},
                onSend = {}
            )
        }
        val image = scene.render()
        scene.close()
        val bytes = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
        File(System.getProperty("java.io.tmpdir"), "dropbox-windows-$name.png").writeBytes(bytes)
        return bytes.size
    }

    @Test
    fun everyStateDraws() {
        assertTrue(render("empty", emptyList()) > 1000)
        assertTrue(render("dragover", emptyList(), dragOver = true) > 1000)
        assertTrue(render("files", entries, pinned = true) > 1000)
        assertTrue(render("sending", entries, sending = true) > 1000)
    }
}
