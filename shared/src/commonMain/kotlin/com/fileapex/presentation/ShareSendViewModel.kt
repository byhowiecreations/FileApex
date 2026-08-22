package com.fileapex.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileapex.di.FileApexServices
import com.fileapex.domain.share.IncomingShareFile
import com.fileapex.domain.share.IncomingSharePayload
import com.fileapex.domain.transfer.MultiCopyDeviceOption
import com.fileapex.domain.transfer.MultiCopySource
import com.fileapex.domain.transfer.verifiedFromDisk
import com.fileapex.domain.transfer.ShareStagingCleanup
import com.fileapex.platform.recordDirectShareTargetUsed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareSendUiState(
    val fileNames: List<String> = emptyList(),
    val isPreparing: Boolean = true,
    val options: List<MultiCopyDeviceOption> = emptyList(),
    val selectedDeviceIds: Set<String> = emptySet(),
    val onlineDeviceIds: Set<String> = emptySet(),
    val isSending: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val sendCompleted: Boolean = false,
    val isDirectSend: Boolean = false
)

/**
 * Android system Share sheet → device picker or one-tap Direct Share shortcut send.
 * All outbound work goes through [com.fileapex.domain.transfer.TransferManager].
 */
class ShareSendViewModel(
    private val payload: IncomingSharePayload,
    private val directTargetDeviceId: String? = null
) : ViewModel() {
    private val transferManager = FileApexServices.transferManager

    private val _uiState = MutableStateFlow(
        ShareSendUiState(
            fileNames = payload.files.map { it.fileName },
            isPreparing = true,
            isDirectSend = !directTargetDeviceId.isNullOrBlank()
        )
    )
    val uiState: StateFlow<ShareSendUiState> = _uiState.asStateFlow()

    init {
        val targetId = directTargetDeviceId?.trim().orEmpty()
        if (targetId.isNotEmpty()) {
            sendDirectToDevice(targetId)
        } else {
            prepareDestinations()
        }
    }

    fun toggleDevice(deviceId: String) {
        _uiState.update { state ->
            val next = if (deviceId in state.selectedDeviceIds) {
                state.selectedDeviceIds - deviceId
            } else {
                state.selectedDeviceIds + deviceId
            }
            state.copy(selectedDeviceIds = next)
        }
    }

    fun send() {
        val state = _uiState.value
        if (state.isSending || state.selectedDeviceIds.isEmpty()) return
        val selected = state.options.filter { it.deviceId in state.selectedDeviceIds }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            runSend(selected)
        }
    }

    fun cancelCleanup() {
        cleanupStaging()
    }

    private fun prepareDestinations() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isPreparing = true, errorMessage = null, statusMessage = "Preparing…")
            }
            runCatching {
                transferManager.awaitReady()
                val onlineIds = FileApexServices.presenceMonitor.onlineDeviceIds.value
                val options = transferManager.buildShareSheetDeviceOptions()
                onlineIds to options
            }.fold(
                onSuccess = { (onlineIds, options) ->
                    _uiState.update {
                        it.copy(
                            isPreparing = false,
                            options = options,
                            onlineDeviceIds = onlineIds,
                            statusMessage = "${payload.files.size} file(s) · ${options.size} paired device(s)"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isPreparing = false,
                            options = emptyList(),
                            errorMessage = error.message ?: "Could not load devices"
                        )
                    }
                }
            )
        }
    }

    private fun sendDirectToDevice(deviceId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isPreparing = true,
                    isSending = true,
                    errorMessage = null,
                    statusMessage = "Sending…"
                )
            }
            runCatching {
                transferManager.awaitReady()
                transferManager.resolveRemoteDeviceOptionsForImmediateSend(listOf(deviceId))
            }.fold(
                onSuccess = { selected ->
                    runSend(
                        selected,
                        recordDirectShareOnSuccess = deviceId,
                        skipTransferPrepare = true
                    )
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isPreparing = false,
                            isSending = false,
                            errorMessage = error.message ?: "Send failed"
                        )
                    }
                }
            )
        }
    }

    private suspend fun runSend(
        selected: List<MultiCopyDeviceOption>,
        recordDirectShareOnSuccess: String? = null,
        skipTransferPrepare: Boolean = false
    ) {
        _uiState.update {
            it.copy(isSending = true, errorMessage = null, statusMessage = "Sending…")
        }
        runCatching {
            transferManager.awaitReady()
            val sources = payload.files.map { it.toSource().verifiedFromDisk() }
            FileApexServices.transferQueue.sendOrQueue(sources, selected, skipTransferPrepare)
        }.fold(
            onSuccess = { outcome ->
                if (!outcome.hadQueue) {
                    cleanupStaging()
                }
                val batch = outcome.batch
                if (batch != null && !batch.allFailed) {
                    recordDirectShareOnSuccess?.let { recordDirectShareTargetUsed(it) }
                }
                val allFailed = batch?.allFailed == true && !outcome.hadQueue && !outcome.hadRelay
                val confirmOnly = outcome.message.startsWith("Confirm Cellular")
                _uiState.update {
                    it.copy(
                        isPreparing = false,
                        isSending = false,
                        sendCompleted = !allFailed && !confirmOnly,
                        statusMessage = outcome.message,
                        errorMessage = outcome.message.takeIf { allFailed && !confirmOnly }
                    )
                }
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(
                        isPreparing = false,
                        isSending = false,
                        errorMessage = error.message ?: "Send failed"
                    )
                }
            }
        )
    }

    private fun IncomingShareFile.toSource(): MultiCopySource.Local =
        MultiCopySource.Local(
            fileName = fileName,
            sizeBytes = sizeBytes,
            absolutePath = absolutePath
        )

    private fun cleanupStaging() {
        ShareStagingCleanup.deleteSessionRootsForPaths(
            payload.files.map { it.absolutePath }
        )
    }
}
