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
        openPdfPreview: (RemoteFileItem) -> Unit = {},
        openDownloadPrompt: (RemoteFileItem) -> Unit = {}
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
            openPdfPreview,
            openDownloadPrompt
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

    @Test fun activatingAnyOtherRemoteFileOffersTheDownloadDialog() {
        val remoteTarget = BrowseTarget.Remote("peer-1", "Phone", "192.168.1.50", 8080, "/")
        val state = MutableStateFlow(ExplorerUiState())
        val prompted = mutableListOf<String>()
        val selection = actions(state, remoteTarget, openDownloadPrompt = { prompted += it.name })
        listOf("app.apk", "setup.exe", "archive.zip", "blob.bin", "song.mp3", "clip.mp4").forEach {
            selection.activateFile(RemoteFileItem(it, "/x/$it", 1024L, 0L, false, "application/octet-stream"))
        }
        assertEquals(listOf("app.apk", "setup.exe", "archive.zip", "blob.bin", "song.mp3", "clip.mp4"), prompted)
    }

    @Test fun activatingARemotePdfOpensThePdfPreviewNotTheDownloadPrompt() {
        val remoteTarget = BrowseTarget.Remote("peer-1", "Phone", "192.168.1.50", 8080, "/")
        val state = MutableStateFlow(ExplorerUiState())
        val pdfs = mutableListOf<String>()
        val prompted = mutableListOf<String>()
        val selection = actions(state, remoteTarget, { pdfs += it.name }, { prompted += it.name })
        selection.activateFile(RemoteFileItem("guide.pdf", "/docs/guide.pdf", 2048L, 0L, false, "application/pdf"))
        assertEquals(listOf("guide.pdf"), pdfs)
        assertTrue(prompted.isEmpty())
    }
}
