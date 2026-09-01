package com.fileapex.domain.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalTransferTreeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun expandAbsolutePathsRecursivelyPreservesSubfolderHierarchy() {
        val rootDir = tempFolder.newFolder("MyProject")
        val subDir = File(rootDir, "src").apply { mkdirs() }
        val emptyDir = File(rootDir, "empty").apply { mkdirs() }

        val rootFile = File(rootDir, "README.md").apply { writeText("Hello") }
        val subFile = File(subDir, "Main.kt").apply { writeText("fun main() {}") }

        val expanded = LocalTransferTree.expandAbsolutePaths(listOf(rootDir.absolutePath))

        assertEquals(5, expanded.size)

        val rootEntry = expanded.find { it.absolutePath == rootDir.absolutePath }
        assertTrue(rootEntry != null && rootEntry.isDirectory && rootEntry.relativeDestPath == "MyProject")

        val subEntry = expanded.find { it.absolutePath == subDir.absolutePath }
        assertTrue(subEntry != null && subEntry.isDirectory && subEntry.relativeDestPath == "MyProject/src")

        val emptyEntry = expanded.find { it.absolutePath == emptyDir.absolutePath }
        assertTrue(emptyEntry != null && emptyEntry.isDirectory && emptyEntry.relativeDestPath == "MyProject/empty")

        val rootFileEntry = expanded.find { it.absolutePath == rootFile.absolutePath }
        assertTrue(rootFileEntry != null && !rootFileEntry.isDirectory && rootFileEntry.relativeDestPath == "MyProject/README.md")

        val subFileEntry = expanded.find { it.absolutePath == subFile.absolutePath }
        assertTrue(subFileEntry != null && !subFileEntry.isDirectory && subFileEntry.relativeDestPath == "MyProject/src/Main.kt")
    }

    @Test
    fun expandSingleFileProducesDirectRelativeDestPath() {
        val singleFile = tempFolder.newFile("notes.txt").apply { writeText("Test") }
        val expanded = LocalTransferTree.expandAbsolutePaths(listOf(singleFile.absolutePath))

        assertEquals(1, expanded.size)
        val entry = expanded.first()
        assertEquals("notes.txt", entry.fileName)
        assertEquals("notes.txt", entry.relativeDestPath)
        assertEquals(false, entry.isDirectory)
    }

    @Test
    fun expandDirectoryFiltersOutOsJunkFiles() {
        val rootDir = tempFolder.newFolder("JunkTest")
        File(rootDir, ".DS_Store").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        File(rootDir, "._test.txt").apply { writeText("AppleDouble") }
        File(rootDir, "Thumbs.db").apply { writeText("thumbnails") }
        File(rootDir, "desktop.ini").apply { writeText("config") }
        File(rootDir, "valid.txt").apply { writeText("Valid file") }

        val expanded = LocalTransferTree.expandAbsolutePaths(listOf(rootDir.absolutePath))

        // Only rootDir and valid.txt should be present
        assertEquals(2, expanded.size)
        assertTrue(expanded.any { it.absolutePath == rootDir.absolutePath && it.isDirectory })
        assertTrue(expanded.any { it.fileName == "valid.txt" && !it.isDirectory })
    }
}
