package com.fileapex.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathConfinementTest {
    private fun withTree(block: (root: File, outside: File) -> Unit) {
        val base = Files.createTempDirectory("fileapex-confine").toFile()
        try {
            val root = File(base, "shared").also { it.mkdirs() }
            File(root, "docs/inner").mkdirs()
            val outside = File(base, "secret").also { it.mkdirs() }
            block(root, outside)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun foldersAndNewFilesInsideTheRootAreAllowedForBrowsing() = withTree { root, _ ->
        assertTrue(PathUtils.isConfinedOnDisk(root.absolutePath, root.absolutePath))
        assertTrue(PathUtils.isConfinedOnDisk(File(root, "docs/inner").absolutePath, root.absolutePath))
        assertTrue(PathUtils.isConfinedOnDisk(File(root, "docs/not-there-yet/file.bin").absolutePath, root.absolutePath))
    }

    @Test
    fun aParentSegmentCannotClimbOutOfTheRoot() = withTree { root, outside ->
        assertFalse(PathUtils.isConfinedOnDisk("${root.absolutePath}/../secret/key", root.absolutePath))
        assertFalse(PathUtils.isConfinedOnDisk("${root.absolutePath}/docs/../../secret", root.absolutePath))
        assertTrue(PathUtils.hasParentSegment("a/../b"))
        assertTrue(PathUtils.hasParentSegment("a\\..\\b"))
        assertFalse(PathUtils.hasParentSegment("a/..b/c"))
        assertFalse(PathUtils.isConfinedOnDisk(outside.absolutePath, root.absolutePath))
    }

    @Test
    fun aLinkInsideTheRootDoesNotLeadOut() = withTree { root, outside ->
        val link = File(root, "docs/link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertFalse(PathUtils.isConfinedOnDisk(File(link, "key").absolutePath, root.absolutePath))
    }

    @Test
    fun theRootCanItselfBeReachedThroughALink() = withTree { root, _ ->
        val alias = File(root.parentFile, "alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        assertTrue(PathUtils.isConfinedOnDisk(File(alias, "docs").absolutePath, root.absolutePath))
    }

    @Test
    fun aFileNameIsReducedToItsLastSegmentAndDotNamesAreRefused() {
        assertEquals("a.txt", PathUtils.safeFileName("../../etc/a.txt"))
        assertEquals("a.txt", PathUtils.safeFileName("dir\\a.txt"))
        assertEquals("plain", PathUtils.safeFileName(" plain "))
        assertNull(PathUtils.safeFileName(".."))
        assertNull(PathUtils.safeFileName("x/.."))
        assertNull(PathUtils.safeFileName(""))
    }
}
