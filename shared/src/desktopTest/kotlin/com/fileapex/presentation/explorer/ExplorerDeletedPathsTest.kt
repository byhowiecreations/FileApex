package com.fileapex.presentation.explorer

import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.presentation.ExplorerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ExplorerDeletedPathsTest {
    private fun dir(path: String) = RemoteFileItem(path.substringAfterLast('/'), path, 0L, 0L, true, "inode/directory")
    private fun file(path: String) = RemoteFileItem(path.substringAfterLast('/'), path, 10L, 0L, false, "text/plain")

    private val photos = dir("/root/Photos")
    private val docs = dir("/root/Docs")
    private val inPhotos = file("/root/Photos/a.jpg")

    private val twoPanes = ExplorerUiState(
        currentPath = "/root/Photos",
        panePath = "/root",
        paneDirectories = listOf(photos, docs),
        paneFiles = emptyList(),
        contentDirectories = emptyList(),
        contentFiles = listOf(inPhotos),
        selectedFolderPath = "/root/Photos"
    )

    @Test
    fun deletingTheFolderShownOnTheRightGoesBackToThePanesFolder() {
        val after = twoPanes.afterDeleting(listOf("/root/Photos"))
        assertNull(after.selectedFolderPath)
        assertEquals("/root", after.currentPath)
        assertEquals(after.paneDirectories, after.contentDirectories)
        assertEquals(after.paneFiles, after.contentFiles)
    }

    @Test
    fun deletingAFolderAbovetheOneShownDoesTheSame() {
        val deep = twoPanes.copy(currentPath = "/root/Photos/2026", selectedFolderPath = "/root/Photos/2026")
        val after = deep.afterDeleting(listOf("/root/Photos"))
        assertNull(after.selectedFolderPath)
        assertEquals("/root", after.currentPath)
    }

    @Test
    fun deletingSomethingElseLeavesTheViewAlone() {
        assertSame(twoPanes, twoPanes.afterDeleting(listOf("/root/Docs")))
        assertSame(twoPanes, twoPanes.afterDeleting(listOf("/root/PhotosOld")))
    }

    @Test
    fun aFolderNamedLikeAPrefixIsNotMistakenForAParent() {
        val state = twoPanes.copy(currentPath = "/root/Photos2", selectedFolderPath = "/root/Photos2")
        assertSame(state, state.afterDeleting(listOf("/root/Photos")))
    }

    @Test
    fun withNoFolderSelectedTheSingleListingIsKept() {
        val single = twoPanes.copy(currentPath = "/root", selectedFolderPath = null, contentDirectories = listOf(photos, docs))
        assertSame(single, single.afterDeleting(listOf("/root/Photos/a.jpg")))
    }

    @Test
    fun windowsStylePathsAreComparedWithTheirSlashesNormalized() {
        val state = twoPanes.copy(currentPath = "C:/Users/me/Photos", panePath = "C:/Users/me", selectedFolderPath = "C:/Users/me/Photos")
        assertNull(state.afterDeleting(listOf("C:\\Users\\me\\Photos")).selectedFolderPath)
    }
}
