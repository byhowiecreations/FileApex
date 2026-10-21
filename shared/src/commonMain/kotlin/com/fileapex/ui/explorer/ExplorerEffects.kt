package com.fileapex.ui.explorer

import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.platform.openLocalFile
import com.fileapex.platform.revealInFolder
import com.fileapex.ui.ExplorerHeaderCommands

@Composable
internal fun ExplorerScope.ExplorerEffects() {
    with(inputs) { with(frame) { with(live) {
    LaunchedEffect(viewModel) { viewModel.onScreenShown() }
    LaunchedEffect(Unit) {
        com.fileapex.platform.StorageToolsEvents.closeRequests.collect { toolsOpen = false }
    }
    LaunchedEffect(state.isRefreshing, viewModel) {
        onRegisterRefresh?.invoke(state.isRefreshing, viewModel::refresh)
    }
    LaunchedEffect(state.isSelectionMode, state.canPaste, viewModel, toolsAvailable) {
        onRegisterHeaderCommands?.invoke(
            if (state.isSelectionMode) {
                null
            } else {
                ExplorerHeaderCommands(
                    onSelect = viewModel::enterSelectionMode,
                    onPaste = if (state.canPaste) viewModel::pasteHere else null,
                    onOpenTools = if (toolsAvailable) ({ toolsOpen = true }) else null
                )
            }
        )
    }
    FileApexBackHandler(enabled = backEnabled) {
        when {
            state.showMultiCopyPicker -> viewModel.dismissMultiCopyPicker()
            state.showMultiCopyIntro -> viewModel.dismissMultiCopyIntro()
            !viewModel.handleBackNavigation() -> onBack()
        }
    }
    LaunchedEffect(state.statusMessage, state.errorMessage) {
        state.statusMessage?.let { message ->
            val downloaded = state.lastDownloadedPaths
            if (downloaded.isEmpty()) {
                snackbarHostState.showSnackbar(message)
                viewModel.dismissMessages()
                return@let
            }
            viewModel.dismissMessages()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (downloaded.size == 1) openActionLabel else showInFolderLabel,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                if (downloaded.size == 1) {
                    openLocalFile(downloaded.first())
                } else {
                    revealInFolder(downloaded.first())
                }
            }
        }
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessages()
        }
    }
    } } }
}
