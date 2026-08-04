package com.fileapex.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileapex.di.FileApexServices
import com.fileapex.domain.transfer.MultiCopyDeviceOption
import com.fileapex.domain.transfer.PendingTransferItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TransferQueueUiState(
    val items: List<PendingTransferItem> = emptyList(),
    val pendingCount: Int = 0,
    val deviceOptions: List<MultiCopyDeviceOption> = emptyList(),
    val showDevicePicker: Boolean = false,
    val pendingDropPaths: List<String> = emptyList(),
    val selectedDeviceIds: Set<String> = emptySet(),
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val isLoadingDevices: Boolean = false
)

class TransferQueueViewModel : ViewModel() {
    private val queue = FileApexServices.transferQueue
    private val transferManager = FileApexServices.transferManager

    private val _uiState = MutableStateFlow(TransferQueueUiState())
    val uiState: StateFlow<TransferQueueUiState> = _uiState.asStateFlow()

    val pendingCount: StateFlow<Int> = queue.pendingCount.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0
    )

    init {
        viewModelScope.launch {
            queue.pendingItems.collect { items ->
                _uiState.update {
                    it.copy(items = items, pendingCount = items.size)
                }
            }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            runCatching { queue.remove(id) }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "Could not remove queued file")
                }
            }
        }
    }

    fun onDesktopFilesDropped(paths: List<String>) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    pendingDropPaths = paths,
                    showDevicePicker = true,
                    selectedDeviceIds = emptySet(),
                    isLoadingDevices = true,
                    errorMessage = null
                )
            }
            runCatching {
                transferManager.awaitReady()
                val peers = FileApexServices.deviceRepository.listDevices()
                transferManager.resolveRemoteDeviceOptions(peers.map { it.deviceId })
            }.fold(
                onSuccess = { options ->
                    _uiState.update {
                        it.copy(
                            deviceOptions = options,
                            isLoadingDevices = false
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoadingDevices = false,
                            showDevicePicker = false,
                            pendingDropPaths = emptyList(),
                            errorMessage = error.message ?: "Could not load devices"
                        )
                    }
                }
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

    fun dismissDevicePicker() {
        _uiState.update {
            it.copy(
                showDevicePicker = false,
                pendingDropPaths = emptyList(),
                selectedDeviceIds = emptySet(),
                deviceOptions = emptyList()
            )
        }
    }

    fun confirmEnqueueDropped() {
        val state = _uiState.value
        val paths = state.pendingDropPaths
        val deviceIds = state.selectedDeviceIds.toList()
        if (paths.isEmpty() || deviceIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                transferManager.awaitReady()
                queue.enqueueLocalPaths(paths, deviceIds)
            }.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            showDevicePicker = false,
                            pendingDropPaths = emptyList(),
                            selectedDeviceIds = emptySet(),
                            deviceOptions = emptyList(),
                            statusMessage = "Added to queue — will send when peer is on local Wi‑Fi."
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: "Could not queue files")
                    }
                }
            )
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }
}
