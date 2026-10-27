package com.fileapex.ui.explorer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.ExplorerViewModel
import com.fileapex.ui.ExplorerHeaderCommands
import com.fileapex.ui.ExplorerPaneId

internal class ExplorerInputs(
    val target: BrowseTarget,
    val onBack: () -> Unit,
    val titleOverride: String?,
    val embeddedInCompactShell: Boolean,
    val onOpenTransferQueue: () -> Unit = {},
    val onRegisterRefresh: (((isRefreshing: Boolean, doRefresh: () -> Unit) -> Unit))? = null,
    val onRegisterHeaderCommands: ((ExplorerHeaderCommands?) -> Unit)? = null,
    val onExitApp: (() -> Unit)? = null,
    val paneId: ExplorerPaneId,
    val hostSplit: Boolean,
    val layoutExpanded: Boolean,
    val paneLabel: String?,
    val secondaryTarget: BrowseTarget?,
    val readyDevices: List<com.fileapex.presentation.DeviceListRow>,
    val onSelectSecondaryLocal: () -> Unit = {},
    val onSelectSecondaryDevice: (String) -> Unit = {},
    val viewModelKey: String,
    val showTopBar: Boolean,
    val filterBarLeading: (@Composable (modifier: Modifier, compact: Boolean) -> Unit)? = null,
    val onDropBoxFiles: ((List<String>) -> Unit)? = null,
    val onPickFiles: ((List<String>) -> Unit)? = null,
    val viewModel: ExplorerViewModel
)
