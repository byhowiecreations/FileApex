package com.fileapex.platform

import org.junit.Test
import org.junit.Assert.assertEquals

class StorageAnalysisTest {
    private fun file(path: String, size: Long, modified: Long = 1_000L) =
        StorageFileEntry(path, path.substringAfterLast('/'), size, modified, "")

    @Test
    fun duplicatesRequireMatchingContentNotJustSize() {
        val files = listOf(
            file("/r/a.bin", 100_000), file("/r/b.bin", 100_000), file("/r/c.bin", 100_000), file("/r/tiny", 10)
        )
        val content = mapOf("/r/a.bin" to "x", "/r/b.bin" to "x", "/r/c.bin" to "y")
        val groups = findDuplicateGroups(files, { path, _ -> content[path] ?: path })
        assertEquals(1, groups.size)
        assertEquals(listOf("/r/a.bin", "/r/b.bin"), groups.single().files.map { it.path })
        assertEquals(100_000L, groups.single().wastedBytes)
    }

    @Test
    fun prefixMatchWithDifferentTailIsNotADuplicate() {
        val files = listOf(file("/r/a", 200_000), file("/r/b", 200_000))
        val groups = findDuplicateGroups(files, { path, max -> if (max < 0) path else "same-head" })
        assertEquals(0, groups.size)
    }

    @Test
    fun folderUsagesRollUpToParents() {
        val files = listOf(file("/r/a/x", 10), file("/r/a/b/y", 5), file("/r/c/z", 1))
        val usages = folderUsages(files, "/r")
        assertEquals(15L, usages.getValue("/r/a").bytes)
        assertEquals(5L, usages.getValue("/r/a/b").bytes)
        assertEquals(listOf("/r/a", "/r/c"), childFolders(usages, "/r").map { it.path })
    }

    @Test
    fun staleFilesRespectKindAndAge() {
        val day = 86_400_000L
        val now = 400 * day
        val files = listOf(
            file("/s/Pictures/Screenshots/old.png", 5, now - 100 * day),
            file("/s/Pictures/Screenshots/new.png", 9, now - 2 * day),
            file("/s/Download/old.zip", 7, now - 200 * day),
        )
        assertEquals(listOf("old.png"), staleFiles(files, CleanupKind.Screenshots, 30, now).map { it.name })
        assertEquals(listOf("old.zip"), staleFiles(files, CleanupKind.Downloads, 90, now).map { it.name })
    }
}
