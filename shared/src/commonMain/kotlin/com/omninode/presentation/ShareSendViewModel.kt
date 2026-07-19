package com.omninode.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omninode.di.OmniNodeServices
import com.omninode.domain.share.IncomingShareFile
import com.omninode.domain.share.IncomingSharePayload
import com.omninode.domain.transfer.MultiCopyDeviceOption
import com.omninode.domain.transfer.MultiCopySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

data class ShareSendUiState(
    val fileNames: List<String> = emptyList(),
    val isPreparing: Boolean = true,
    val options: List<MultiCopyDeviceOption> = emptyList(),
    val selectedDeviceIds: Set<String> = emptySet(),
    val isSending: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val sendCompleted: Boolean = false
)

/**
 * Android system Share sheet → device picker.
 * All outbound work goes through [com.omninode.domain.transfer.TransferManager].
 */
class ShareSendViewModel(
    private val payload: IncomingSharePayload
) : ViewModel() {
    private val transferManager = OmniNodeServices.transferManager
    private val identity get() = OmniNodeServices.localIdentity

    private val _uiState = MutableStateFlow(
        ShareSendUiState(
            fileNames = payload.files.map { it.fileName },
            isPreparing = true
        )
    )
    val uiState: StateFlow<ShareSendUiState> = _uiState.asStateFlow()

    init {
        prepareDestinations()
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
            _uiState.update {
                it.copy(isSending = true, errorMessage = null, statusMessage = "Sending…")
            }
            runCatching {
                transferManager.awaitReady()
                val sources = payload.files.map { it.toSource() }
                transferManager.sendToDevices(sources, selected)
            }.fold(
                onSuccess = { batch ->
                    cleanupStaging()
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            sendCompleted = !batch.allFailed,
                            statusMessage = batch.summaryMessage,
                            errorMessage = batch.summaryMessage.takeIf { batch.allFailed }
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            errorMessage = error.message ?: "Send failed"
                        )
                    }
                }
            )
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
                transferManager.buildInAppDeviceOptions(sourceDeviceId = identity.deviceId)
            }.fold(
                onSuccess = { options ->
                    val remotes = options.filter { !it.isLocal }
                    _uiState.update {
                        it.copy(
                            isPreparing = false,
                            options = options,
                            statusMessage = when {
                                remotes.isEmpty() && options.any { it.isLocal } ->
                                    "No online paired devices. You can still save to This device."
                                remotes.isEmpty() ->
                                    "No online destination devices. Pair a device in OmniNode first."
                                else ->
                                    "${payload.files.size} file(s) · ${remotes.size} online device(s)"
                            }
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isPreparing = false,
                            errorMessage = error.message ?: "Could not load devices"
                        )
                    }
                }
            )
        }
    }

    private fun IncomingShareFile.toSource(): MultiCopySource.Local =
        MultiCopySource.Local(
            fileName = fileName,
            sizeBytes = sizeBytes,
            absolutePath = absolutePath
        )

    private fun cleanupStaging() {
        runCatching {
            val first = payload.files.firstOrNull()?.absolutePath ?: return
            val parent = Path(first).parent ?: return
            if (SystemFileSystem.exists(parent)) {
                SystemFileSystem.list(parent).forEach { child ->
                    runCatching { SystemFileSystem.delete(child) }
                }
                runCatching { SystemFileSystem.delete(parent) }
            }
        }
    }
}
