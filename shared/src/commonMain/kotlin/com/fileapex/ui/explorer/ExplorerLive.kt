package com.fileapex.ui.explorer

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fileapex.presentation.ExplorerSortMode

internal class ExplorerLive {
    var toolsOpen by mutableStateOf(false)
    var pinText by mutableStateOf("")
    var sortMode by mutableStateOf(ExplorerSortMode.Name)
    val snackbarHostState = SnackbarHostState()
}
