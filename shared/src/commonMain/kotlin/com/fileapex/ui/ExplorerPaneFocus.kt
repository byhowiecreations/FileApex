package com.fileapex.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

enum class ExplorerPaneId {
    Primary,
    Secondary,
}

class ExplorerPaneFocus {
    var active by mutableStateOf(ExplorerPaneId.Primary)
}

val LocalExplorerPaneFocus = staticCompositionLocalOf<ExplorerPaneFocus?> { null }
