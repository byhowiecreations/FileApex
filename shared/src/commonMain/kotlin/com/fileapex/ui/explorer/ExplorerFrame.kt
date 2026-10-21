package com.fileapex.ui.explorer

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.presentation.ExplorerUiState
import com.fileapex.ui.ExplorerPaneFocus

internal class ExplorerFrame(
    val state: ExplorerUiState,
    val toolsAvailable: Boolean,
    val showCopyFabs: Boolean,
    val topBarTitle: String,
    val paneFocus: ExplorerPaneFocus?,
    val splitOpen: Boolean,
    val folderPane: Boolean,
    val backEnabled: Boolean,
    val visibleDirectories: List<RemoteFileItem>,
    val visibleFiles: List<RemoteFileItem>,
    val openActionLabel: String,
    val showInFolderLabel: String,
    val ownedFocus: ExplorerPaneFocus,
    val jadedOrbital: Boolean,
    val filterQueryState: MutableState<String>
) {
    var filterQuery by filterQueryState
}
