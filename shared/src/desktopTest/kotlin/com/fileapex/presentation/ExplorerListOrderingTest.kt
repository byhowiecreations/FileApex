package com.fileapex.presentation

import com.fileapex.domain.model.RemoteFileItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorerListOrderingTest {
    private fun item(name: String, size: Long, modified: Long, dir: Boolean = false) = RemoteFileItem(
        name = name,
        absolutePath = "/root/$name",
        sizeBytes = size,
        lastModified = modified,
        isDirectory = dir,
        mimeType = if (dir) "inode/directory" else "application/octet-stream"
    )

    private val items = listOf(
        item("beta.txt", size = 10, modified = 300),
        item("Alpha.jpg", size = 500, modified = 100),
        item("gamma.pdf", size = 50, modified = 200)
    )

    @Test
    fun nameSortIsCaseInsensitive() {
        val names = ExplorerListOrdering.apply(items, "", ExplorerSortMode.Name).map { it.name }
        assertEquals(listOf("Alpha.jpg", "beta.txt", "gamma.pdf"), names)
    }

    @Test
    fun dateSortIsNewestFirst() {
        val names = ExplorerListOrdering.apply(items, "", ExplorerSortMode.Date).map { it.name }
        assertEquals(listOf("beta.txt", "gamma.pdf", "Alpha.jpg"), names)
    }

    @Test
    fun sizeSortIsLargestFirstAndFoldersFallBackToName() {
        val files = ExplorerListOrdering.apply(items, "", ExplorerSortMode.Size).map { it.name }
        assertEquals(listOf("Alpha.jpg", "gamma.pdf", "beta.txt"), files)
        val dirs = listOf(item("zeta", 4096, 1, dir = true), item("eta", 8192, 2, dir = true))
        assertEquals(
            listOf("eta", "zeta"),
            ExplorerListOrdering.apply(dirs, "", ExplorerSortMode.Size).map { it.name }
        )
    }

    @Test
    fun filterMatchesSubstringIgnoringCase() {
        val names = ExplorerListOrdering.apply(items, "  ALP ", ExplorerSortMode.Name).map { it.name }
        assertEquals(listOf("Alpha.jpg"), names)
    }
}
