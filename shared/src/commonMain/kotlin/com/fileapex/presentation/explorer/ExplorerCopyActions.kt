package com.fileapex.presentation.explorer

import androidx.lifecycle.viewModelScope
import com.fileapex.data.clipboard.TransferClipboard
import com.fileapex.data.settings.AppSettings
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.domain.transfer.ExplorerTransferManager
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.platform.DownloadsPaths
import com.fileapex.presentation.ExplorerActionCopy
import com.fileapex.presentation.ExplorerUiState
import com.fileapex.presentation.ExplorerViewModel
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ExplorerCopyActions(
    private val _uiState: MutableStateFlow<ExplorerUiState>,
    private val viewModelScope: CoroutineScope,
    private val settings: AppSettings,
    private val transfers: ExplorerTransferManager,
    private val refresh: () -> Unit,
    private val selectedItems: () -> List<RemoteFileItem>
) {
    fun clearPendingCopy() {
        TransferClipboard.clear()
        _uiState.update { it.copy(canPaste = false, clipboardLabel = null) }
    }

    fun copySelected() {
        val items = selectedItems()
        runCatching {
            val message = transfers.copySelected(items)
            _uiState.update {
                it.copy(
                    isSelectionMode = false,
                    selectedFileIds = emptySet(),
                    canDownloadSelection = false,
                    canPaste = true,
                    clipboardLabel = TransferClipboard.label(),
                    statusMessage = message
                )
            }
        }.onFailure { error ->
            _uiState.update { it.copy(errorMessage = UserFacingErrors.message(error, "copy_failed")) }
        }
    }

    fun copyItem(item: RemoteFileItem) {
        val items = if (item.id in _uiState.value.selectedFileIds) {
            selectedItems()
        } else {
            listOf(item)
        }
        runCatching {
            val message = transfers.copySelected(items)
            _uiState.update {
                it.copy(
                    isSelectionMode = false,
                    selectedFileIds = emptySet(),
                    canDownloadSelection = false,
                    canPaste = true,
                    clipboardLabel = TransferClipboard.label(),
                    statusMessage = message
                )
            }
        }.onFailure { error ->
            _uiState.update { it.copy(errorMessage = UserFacingErrors.message(error, "copy_failed")) }
        }
    }

    fun onMultiCopyFabClick() {
        if (selectedItems().isEmpty()) {
            _uiState.update { it.copy(errorMessage = ExplorerActionCopy.ERROR_SELECT_FILES) }
            return
        }
        if (!settings.multiCopyIntroAcknowledged.value) {
            _uiState.update { it.copy(showMultiCopyIntro = true) }
            return
        }
        openMultiCopyPicker()
    }

    fun sendItemToDevices(item: RemoteFileItem) {
        if (item.id !in _uiState.value.selectedFileIds) {
            _uiState.update {
                it.copy(
                    isSelectionMode = true,
                    selectedFileIds = setOf(item.id)
                )
            }
        }
        if (!settings.multiCopyIntroAcknowledged.value) {
            _uiState.update { it.copy(showMultiCopyIntro = true) }
            return
        }
        openMultiCopyPicker()
    }

    fun acknowledgeMultiCopyIntro() {
        settings.setMultiCopyIntroAcknowledged(true)
        _uiState.update { it.copy(showMultiCopyIntro = false) }
        openMultiCopyPicker()
    }

    fun dismissMultiCopyIntro() {
        _uiState.update { it.copy(showMultiCopyIntro = false) }
    }

    /** Closes the picker and leaves a running send alone; it reports through the status message. */
    fun hideMultiCopyPicker() {
        _uiState.update { it.copy(showMultiCopyPicker = false) }
    }

    fun dismissMultiCopyPicker() {
        _uiState.update {
            it.copy(
                showMultiCopyPicker = false,
                multiCopyOptions = emptyList(),
                selectedMultiCopyDeviceIds = emptySet()
            )
        }
    }

    fun toggleMultiCopyDevice(deviceId: String) {
        _uiState.update { state ->
            val next = if (deviceId in state.selectedMultiCopyDeviceIds) {
                state.selectedMultiCopyDeviceIds - deviceId
            } else {
                state.selectedMultiCopyDeviceIds + deviceId
            }
            state.copy(selectedMultiCopyDeviceIds = next)
        }
    }

    fun confirmMultiCopy() {
        val items = selectedItems()
        if (items.isEmpty()) {
            _uiState.update { it.copy(errorMessage = ExplorerActionCopy.ERROR_SELECT_FILES) }
            return
        }
        val selectedIds = _uiState.value.selectedMultiCopyDeviceIds
        val selectedDevices = _uiState.value.multiCopyOptions.filter { it.deviceId in selectedIds }
        if (selectedDevices.isEmpty()) {
            _uiState.update { it.copy(errorMessage = AppI18n.t("select_destination_device")) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isMultiCopying = true, errorMessage = null) }
            cancellableCatching {
                val sources = transfers.sourcesFrom(items)
                transfers.sendOrQueue(sources, selectedDevices)
            }.fold(
                onSuccess = { outcome ->
                    val batch = outcome.batch
                    val failCount = batch?.results?.sumOf { it.failures.size } ?: 0
                    val message = when {
                        outcome.hadQueue && (batch == null || failCount == 0) -> outcome.message
                        failCount == 0 && batch != null -> {
                            val deviceLabel = if (selectedDevices.size == 1) {
                                selectedDevices.first().deviceName
                            } else {
                                AppI18n.plural("device_count", selectedDevices.size)
                            }
                            if (items.size == 1) {
                                AppI18n.t("file_sent_to", deviceLabel)
                            } else {
                                AppI18n.t("files_sent_to", deviceLabel)
                            }.let { base ->
                                if (outcome.hadQueue) "$base ${outcome.message}" else base
                            }

                        }
                        batch?.allFailed == true && !outcome.hadQueue -> ExplorerActionCopy.ERROR_SEND_FAILED
                        else -> outcome.message.ifBlank {
                            ExplorerActionCopy.sendFinishedWithErrors(failCount)
                        }
                    }
                    _uiState.update {
                        it.copy(
                            isMultiCopying = false,
                            showMultiCopyPicker = false,
                            multiCopyOptions = emptyList(),
                            selectedMultiCopyDeviceIds = emptySet(),
                            isSelectionMode = false,
                            selectedFileIds = emptySet(),
                            canDownloadSelection = false,
                            canPaste = TransferClipboard.hasContent(),
                            statusMessage = message,
                            errorMessage = batch?.results
                                ?.flatMap { it.failures.values }
                                ?.firstOrNull()
                                ?.takeIf { batch.allFailed && !outcome.hadQueue }
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isMultiCopying = false,
                            errorMessage = error.message ?: ExplorerActionCopy.ERROR_SEND_FAILED
                        )
                    }
                }
            )
        }
    }

    private fun openMultiCopyPicker() {
        _uiState.update {
            it.copy(
                showMultiCopyPicker = true,
                selectedMultiCopyDeviceIds = emptySet()
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val options = transfers.buildMultiCopyOptions()
            _uiState.update {
                it.copy(multiCopyOptions = options)
            }
        }
    }

    fun downloadItem(item: RemoteFileItem) {
        val items = if (item.id in _uiState.value.selectedFileIds) {
            selectedItems()
        } else {
            listOf(item)
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, errorMessage = null) }
            cancellableCatching {
                transfers.downloadRemote(items)
            }.fold(
                onSuccess = { paths ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            isSelectionMode = false,
                            selectedFileIds = emptySet(),
                            canDownloadSelection = false,
                            canPaste = TransferClipboard.hasContent(),
                            statusMessage = if (paths.size == 1) {
                                AppI18n.t("downloaded_to", paths.first())
                            } else {
                                AppI18n.t("downloaded_files_to", paths.size, DownloadsPaths.displayLabel())
                            },
                            lastDownloadedPaths = paths
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            errorMessage = UserFacingErrors.message(error, "download_failed")
                        )
                    }
                }
            )
        }
    }

    fun downloadSelected() {
        val items = selectedItems()
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, errorMessage = null) }
            cancellableCatching {
                transfers.downloadRemote(items)
            }.fold(
                onSuccess = { paths ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            isSelectionMode = false,
                            selectedFileIds = emptySet(),
                            canDownloadSelection = false,
                            canPaste = TransferClipboard.hasContent(),
                            statusMessage = if (paths.size == 1) {
                                AppI18n.t("downloaded_to", paths.first())
                            } else {
                                AppI18n.t("downloaded_files_to", paths.size, DownloadsPaths.displayLabel())
                            },
                            lastDownloadedPaths = paths
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            errorMessage = UserFacingErrors.message(error, "download_failed")
                        )
                    }
                }
            )
        }
    }

    fun pasteHere() {
        viewModelScope.launch {
            cancellableCatching {
                val paths = transfers.pasteInto(_uiState.value.currentPath)
                _uiState.update {
                    it.copy(
                        statusMessage = if (paths.size == 1) {
                            AppI18n.t("pasted_to", paths.first())
                        } else {
                            AppI18n.t("pasted_files", paths.size)
                        }
                    )
                }
                refresh()
            }.onFailure { error ->
                _uiState.update { it.copy(errorMessage = UserFacingErrors.message(error, "paste_failed")) }
            }
        }
    }
}
