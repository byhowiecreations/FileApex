package com.fileapex.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fileapex.di.FileApexServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TransferQueueUiState(
    val items: List<com.fileapex.domain.transfer.PendingTransferItem> = emptyList(),
    val pendingCount: Int = 0,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class TransferQueueViewModel : ViewModel() {
    private val queue = FileApexServices.transferQueue

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

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }
}
