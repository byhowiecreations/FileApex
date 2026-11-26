package com.fileapex.presentation.explorer

import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.preview.FilePreviewManager
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.ExplorerUiState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cancelling or switching away from an active remote preview load cancels the in-flight request immediately. */
class ExplorerPreviewCancelTest {
    private val target = BrowseTarget.Remote(
        deviceId = "peer-1",
        displayName = "Phone",
        host = "192.168.1.50",
        port = 8080,
        rootPath = "/"
    )

    private fun remoteFile(name: String, mime: String) = RemoteFileItem(
        name = name,
        absolutePath = "/$name",
        sizeBytes = 1_024L,
        lastModified = 0L,
        isDirectory = false,
        mimeType = mime
    )

    @Test
    fun `dismissPreview cancels active remote image and text loads immediately`() = runBlocking {
        for ((name, mime, trigger) in listOf(
            Triple("photo.jpg", "image/jpeg") { actions: ExplorerPreviewActions, item: RemoteFileItem -> actions.openImagePreview(item) },
            Triple("notes.txt", "text/plain") { actions: ExplorerPreviewActions, item: RemoteFileItem -> actions.openTextPreview(item) }
        )) {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val stillRunning = AtomicBoolean(true)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val state = MutableStateFlow(ExplorerUiState())
                val preview = FilePreviewManager(target) { _, _, _ ->
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stillRunning.set(false)
                        cancelled.complete(Unit)
                    }
                }
                val actions = ExplorerPreviewActions(state, scope, target, preview, transfers = null, isRemote = true)
                val item = remoteFile(name, mime)
                trigger(actions, item)
                withTimeout(5_000) { started.await() }
                assertTrue(state.value.isPreviewLoading)

                actions.dismissPreview()
                withTimeout(5_000) { cancelled.await() }

                assertFalse(stillRunning.get())
                assertNull(state.value.previewItem)
                assertFalse(state.value.isPreviewLoading)
                assertNull(state.value.errorMessage)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `openDownloadPrompt and desktop openPdfPreview cancel an in-flight remote preview load`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val state = MutableStateFlow(ExplorerUiState())
            val preview = FilePreviewManager(target) { _, _, _ ->
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val actions = ExplorerPreviewActions(state, scope, target, preview, transfers = null, isRemote = true)
            actions.openImagePreview(remoteFile("slow.png", "image/png"))
            withTimeout(5_000) { started.await() }

            val pdf = remoteFile("report.pdf", "application/pdf")
            actions.openPdfPreview(pdf)
            withTimeout(5_000) { cancelled.await() }

            assertEquals(pdf, state.value.previewItem)
            assertNull(state.value.previewImage)
            assertFalse(state.value.isPreviewLoading)
            assertNull(state.value.errorMessage)
        } finally {
            scope.cancel()
        }
    }
}
