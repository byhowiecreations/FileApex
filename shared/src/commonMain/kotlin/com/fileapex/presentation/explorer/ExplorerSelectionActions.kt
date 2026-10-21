package com.fileapex.presentation.explorer

import androidx.lifecycle.viewModelScope
import com.fileapex.data.clipboard.TransferClipboard
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.preview.FilePreviewManager
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.ExplorerUiState
import com.fileapex.presentation.ExplorerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ExplorerSelectionActions(
    private val _uiState: MutableStateFlow<ExplorerUiState>,
    private val viewModelScope: CoroutineScope,
    private val target: BrowseTarget,
    private val preview: FilePreviewManager,
    private val isRemote: Boolean,
    private val onDirectoryClick: (RemoteFileItem) -> Unit,
    private val openImagePreview: (RemoteFileItem) -> Unit,
    private val openTextPreview: (RemoteFileItem) -> Unit
) {
    /** Anchor for desktop Shift-click range selection. */
    private var selectionAnchorId: String? = null

    fun onFileClick(item: RemoteFileItem) {
        if (_uiState.value.isSelectionMode) {
            toggleFileSelection(item)
            return
        }
        activateFile(item)
    }

    fun onFileLongClick(item: RemoteFileItem) {
        enterSelectionMode(preselect = item)
    }

    /** Desktop: plain click replaces selection with this file or folder. */
    fun selectFileExclusive(item: RemoteFileItem) {
        selectionAnchorId = item.id
        enterSelectionMode(preselect = item)
    }

    /** Desktop: ⌘/Ctrl-click toggles membership in the selection. */
    fun toggleFileSelectionDesktop(item: RemoteFileItem) {
        if (!_uiState.value.isSelectionMode) {
            selectionAnchorId = item.id
            enterSelectionMode(preselect = item)
            return
        }
        toggleFileSelection(item)
        val selected = _uiState.value.selectedFileIds
        if (item.id in selected) {
            selectionAnchorId = item.id
        }
        if (selected.isEmpty()) {
            exitSelectionMode()
        }
    }

    /** Desktop: Shift-click selects a contiguous range from the anchor. */
    fun extendFileSelection(item: RemoteFileItem) {
        val items = _uiState.value.contentDirectories + _uiState.value.contentFiles
        val anchorId = selectionAnchorId
        val anchorIndex = anchorId?.let { id -> items.indexOfFirst { it.id == id } } ?: -1
        val targetIndex = items.indexOfFirst { it.id == item.id }
        if (anchorIndex < 0 || targetIndex < 0) {
            selectFileExclusive(item)
            return
        }
        val from = minOf(anchorIndex, targetIndex)
        val to = maxOf(anchorIndex, targetIndex)
        val rangeIds = items.subList(from, to + 1).map { it.id }.toSet()
        _uiState.update {
            it.copy(
                isSelectionMode = true,
                selectedFileIds = rangeIds,
                canDownloadSelection = isRemote && rangeIds.isNotEmpty(),
                canPaste = false,
                statusMessage = null
            )
        }
    }

    /** Open / preview a file (Android tap outside selection; desktop double-click). */
    fun activateFile(item: RemoteFileItem) {
        when {
            item.isDirectory -> onDirectoryClick(item)
            preview.isImageFile(item) -> openImagePreview(item)
            preview.isTextFile(item) -> openTextPreview(item)
            else -> {
                if (target is BrowseTarget.Local) {
                    com.fileapex.platform.openLocalFile(item.absolutePath, item.name)
                } else {
                    _uiState.update {
                        it.copy(statusMessage = "${item.name} · ${preview.formatBytes(item.sizeBytes)}")
                    }
                }
            }
        }
    }

    fun enterSelectionMode(preselect: RemoteFileItem? = null) {
        val selected = if (preselect != null) setOf(preselect.id) else emptySet()
        if (preselect != null) {
            selectionAnchorId = preselect.id
        }
        _uiState.update {
            it.copy(
                isSelectionMode = true,
                selectedFileIds = selected,
                canDownloadSelection = isRemote && selected.isNotEmpty(),
                canPaste = false,
                statusMessage = null
            )
        }
    }

    fun exitSelectionMode() {
        selectionAnchorId = null
        _uiState.update {
            it.copy(
                isSelectionMode = false,
                selectedFileIds = emptySet(),
                canDownloadSelection = false,
                canPaste = TransferClipboard.hasContent()
            )
        }
    }

    fun toggleFileSelection(item: RemoteFileItem) {
        _uiState.update { state ->
            val next = if (item.id in state.selectedFileIds) {
                state.selectedFileIds - item.id
            } else {
                state.selectedFileIds + item.id
            }
            state.copy(
                selectedFileIds = next,
                canDownloadSelection = isRemote && next.isNotEmpty()
            )
        }
    }

    fun selectedItems(): List<RemoteFileItem> {
        val ids = _uiState.value.selectedFileIds
        val all = _uiState.value.contentDirectories + _uiState.value.contentFiles +
            _uiState.value.paneDirectories + _uiState.value.paneFiles
        return all.distinctBy { it.id }.filter { it.id in ids }
    }
}
