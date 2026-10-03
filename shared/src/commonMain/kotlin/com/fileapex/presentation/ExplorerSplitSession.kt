package com.fileapex.presentation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Right-hand split source. Survives expand/compact and rotation. */
class ExplorerSplitSession : ViewModel() {
    private val _secondaryTarget = MutableStateFlow<BrowseTarget?>(null)
    val secondaryTarget: StateFlow<BrowseTarget?> = _secondaryTarget.asStateFlow()

    fun selectLocal() {
        _secondaryTarget.value = null
    }

    fun select(target: BrowseTarget) {
        _secondaryTarget.value = target
    }
}
