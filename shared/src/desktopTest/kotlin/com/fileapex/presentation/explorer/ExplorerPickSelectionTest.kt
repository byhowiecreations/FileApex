package com.fileapex.presentation.explorer

import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.preview.FilePreviewManager
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.ExplorerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerPickSelectionTest {
    private val image = RemoteFileItem("eye.png", "/d/eye.png", 10, 0, false, "image/png")
    private val folder = RemoteFileItem("APK", "/d/APK", 0, 0, true, "")

    private fun actions(state: MutableStateFlow<ExplorerUiState>): ExplorerSelectionActions {
        val target = BrowseTarget.Local("local", "This device", "/d")
        return ExplorerSelectionActions(state, CoroutineScope(Job() + Dispatchers.Unconfined), target, FilePreviewManager(target), false, {}, {}, {})
    }

    @Test fun sendingTheTickedFilesClearsTheTicksSoTheNextSendStartsEmpty() {
        val state = MutableStateFlow(ExplorerUiState(contentFiles = listOf(image), contentDirectories = listOf(folder), isSelectionMode = true, selectedFileIds = setOf(image.id, folder.id)))
        val sent = mutableListOf<List<String>>()
        actions(state).pickSelected { sent += it }
        assertEquals(listOf(listOf("/d/eye.png")), sent)
        assertFalse(state.value.isSelectionMode)
        assertTrue(state.value.selectedFileIds.isEmpty())
    }
}
