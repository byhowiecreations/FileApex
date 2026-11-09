package com.fileapex.platform

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopDropBoxStagingTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun stagedFilesRejectsDuplicatesAndBlankPaths() {
        val f1 = tempDir.newFile("file1.txt")
        val f2 = tempDir.newFile("file2.txt")

        val state = DesktopDropBoxStagingState()
        state.appendValidPaths(listOf(f1.absolutePath, "", f2.absolutePath, f1.absolutePath))

        assertEquals(2, state.stagedPaths.size)
        assertEquals(listOf(f1.absolutePath, f2.absolutePath), state.stagedPaths)
    }

    @Test
    fun removeFileRemovesSpecificPath() {
        val f1 = tempDir.newFile("a.txt")
        val f2 = tempDir.newFile("b.txt")

        val state = DesktopDropBoxStagingState()
        state.appendValidPaths(listOf(f1.absolutePath, f2.absolutePath))
        state.removePath(f1.absolutePath)

        assertEquals(listOf(f2.absolutePath), state.stagedPaths)
    }

    @Test
    fun clearResetsStagedFilesAndSendingFlag() {
        val f1 = tempDir.newFile("sample.pdf")
        val state = DesktopDropBoxStagingState()
        state.appendValidPaths(listOf(f1.absolutePath))
        state.isSending = true

        state.clear()
        assertTrue(state.stagedPaths.isEmpty())
        assertFalse(state.isSending)
    }

    @Test
    fun stagedEntriesKnowTheirNameAndKindSoDrawingNeverTouchesTheDisk() {
        val file = tempDir.newFile("a.txt")
        val folder = tempDir.newFolder("pictures")
        val state = DesktopDropBoxStagingState()

        state.appendValidPaths(listOf(file.absolutePath, folder.absolutePath))

        assertEquals(
            listOf(
                DesktopStagedEntry(file.absolutePath, "a.txt", isDirectory = false),
                DesktopStagedEntry(folder.absolutePath, "pictures", isDirectory = true)
            ),
            state.stagedEntries
        )
        state.removePath(file.absolutePath)
        assertEquals(listOf(folder.absolutePath), state.stagedEntries.map { it.path })
        state.clear()
        assertTrue(state.stagedEntries.isEmpty())
    }

    @Test
    fun calculatesDestinationDescription() {
        val state = DesktopDropBoxStagingState()
        state.targetDeviceIds = listOf("dev-1")
        state.singleDeviceName = "Ursula-Ubuntu"
        assertEquals("Ursula-Ubuntu", state.destinationSubtitle)

        state.targetDeviceIds = listOf("dev-1", "dev-2")
        state.singleDeviceName = null
        assertEquals("2 destinations", state.formatDestinations { count -> "$count destinations" })
    }
}
