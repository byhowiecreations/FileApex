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

    private fun actions(
        state: MutableStateFlow<ExplorerUiState>,
        target: BrowseTarget = BrowseTarget.Local("local", "This device", "/d"),
        openExternalMedia: (RemoteFileItem) -> Unit = {}
    ): ExplorerSelectionActions {
        return ExplorerSelectionActions(
            state,
            CoroutineScope(Job() + Dispatchers.Unconfined),
            target,
            FilePreviewManager(target),
            target is BrowseTarget.Remote,
            {},
            {},
            {},
            openExternalMedia
        )
    }

    @Test fun sendingTheTickedFilesClearsTheTicksSoTheNextSendStartsEmpty() {
        val state = MutableStateFlow(ExplorerUiState(contentFiles = listOf(image), contentDirectories = listOf(folder), isSelectionMode = true, selectedFileIds = setOf(image.id, folder.id)))
        val sent = mutableListOf<List<String>>()
        actions(state).pickSelected { sent += it }
        assertEquals(listOf(listOf("/d/eye.png")), sent)
        assertFalse(state.value.isSelectionMode)
        assertTrue(state.value.selectedFileIds.isEmpty())
    }

    @Test fun activatingPdfOrAudioRoutesThroughOpenExternalMediaEvenWhenRemote() {
        val remoteTarget = BrowseTarget.Remote("peer-1", "Phone", "192.168.1.50", 8080, "/")
        val state = MutableStateFlow(ExplorerUiState())
        val opened = mutableListOf<String>()
        val pdf = RemoteFileItem("guide.pdf", "/docs/guide.pdf", 2048L, 0L, false, "application/pdf")
        val mp3 = RemoteFileItem("song.mp3", "/music/song.mp3", 4096L, 0L, false, "audio/mpeg")
        val zip = RemoteFileItem("archive.zip", "/docs/archive.zip", 1024L, 0L, false, "application/zip")

        val selection = actions(state, remoteTarget) { opened += it.name }
        selection.activateFile(pdf)
        selection.activateFile(mp3)
        assertEquals(listOf("guide.pdf", "song.mp3"), opened)

        selection.activateFile(zip)
        assertEquals(listOf("guide.pdf", "song.mp3"), opened)
        assertTrue(state.value.statusMessage?.contains("archive.zip") == true)
    }
}
