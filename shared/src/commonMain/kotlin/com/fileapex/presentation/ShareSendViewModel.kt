package com.fileapex.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
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
    val isDirectSend: Boolean = false,
    val filesReady: Boolean = false
)

/**
 * Android system Share sheet → device picker or one-tap Direct Share shortcut send.
 * All outbound work goes through [com.fileapex.domain.transfer.TransferManager].
 */
class ShareSendViewModel(
    payload: IncomingSharePayload,
    private val directTargetDeviceId: String? = null
) : ViewModel() {
    private val transferManager = FileApexServices.transferManager
    private var payload: IncomingSharePayload = payload
    private var directSendStarted = false

    private val _uiState = MutableStateFlow(
        ShareSendUiState(
            fileNames = payload.files.map { it.fileName },
            isPreparing = true,
            isDirectSend = !directTargetDeviceId.isNullOrBlank(),
            filesReady = payload.isStaged
        )
    )
    val uiState: StateFlow<ShareSendUiState> = _uiState.asStateFlow()

    init {
        val targetId = directTargetDeviceId?.trim().orEmpty()
        if (targetId.isEmpty()) {
            prepareDestinations()
        } else if (payload.isStaged) {
            directSendStarted = true
            sendDirectToDevice(targetId)
        }
    }

    /** Staging finishes after the sheet is already on screen. Paths arrive here. */
    fun updatePayload(next: IncomingSharePayload) {
        payload = next
        val ready = next.isStaged
        _uiState.update { it.copy(fileNames = next.files.map { file -> file.fileName }, filesReady = ready) }
        val targetId = directTargetDeviceId?.trim().orEmpty()
        if (ready && targetId.isNotEmpty() && !directSendStarted) {
            directSendStarted = true
            sendDirectToDevice(targetId)
        }
    }

    fun onStagingFailed(message: String) {
        _uiState.update {
            it.copy(
                isPreparing = false,
                isSending = false,
                filesReady = false,
                errorMessage = message
            )
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
        if (!_uiState.value.sendCompleted) {
            cleanupStaging()
        }
    }

    private fun prepareDestinations() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isPreparing = true, errorMessage = null, statusMessage = AppI18n.t("preparing"))
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
                            statusMessage = "${AppI18n.plural("file_count", payload.files.size, payload.files.size.toString())} · ${AppI18n.plural("n_paired_devices", options.size, options.size.toString())}"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isPreparing = false,
                            options = emptyList(),
                            errorMessage = UserFacingErrors.message(error, "could_not_load_devices")
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
                    statusMessage = AppI18n.t("sending")
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
                            errorMessage = UserFacingErrors.message(error, "send_failed")
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
            it.copy(isSending = true, errorMessage = null, statusMessage = AppI18n.t("sending"))
        }
        runCatching {
            check(payload.isStaged) { AppI18n.t("preparing_shared_files") }
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
                val confirmOnly = outcome.needsCellularConfirm
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
                        errorMessage = UserFacingErrors.message(error, "send_failed")
                    )
                }
            }
        )
    }

    private val IncomingSharePayload.isStaged: Boolean
        get() = files.isNotEmpty() && files.all { it.absolutePath.isNotBlank() && it.sizeBytes > 0L }

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
