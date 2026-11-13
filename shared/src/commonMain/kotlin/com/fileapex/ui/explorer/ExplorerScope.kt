package com.fileapex.ui.explorer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.data.settings.traits
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.platform.storageToolsSupported
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.ExplorerListOrdering
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.ExplorerPaneFocus
import com.fileapex.ui.ExplorerPaneId
import com.fileapex.ui.LocalExplorerPaneFocus

internal class ExplorerScope(val inputs: ExplorerInputs, val live: ExplorerLive, val frame: ExplorerFrame)

@Composable
internal fun rememberExplorerScope(inputs: ExplorerInputs): ExplorerScope {
    val live = remember { ExplorerLive() }
    with(inputs) {
        val state by viewModel.uiState.collectAsState()
    val toolsAvailable = target is BrowseTarget.Local && storageToolsSupported()
    val fabMode = ExplorerFabMode.of(onPickFiles != null, state.isSelectionMode, state.selectedFileIds.size, state.isMultiCopying)
    val showCopyFabs = fabMode != ExplorerFabMode.None
    val topBarTitle = titleOverride
        ?: if (target is BrowseTarget.Local) stringRes("local_files") else state.deviceTitle


    val paneFocus = LocalExplorerPaneFocus.current
    val splitOpen = ExplorerSplitPolicy.isSplitOpen(hostSplit, state.viewMode, target)
    val folderPane = layoutExpanded && hostSplit && !splitOpen
    val backEnabled = when {
        paneFocus == null -> true
        !splitOpen && paneId == ExplorerPaneId.Primary -> true
        else -> paneFocus.active == paneId
    }
        val filterQueryState = remember(state.currentPath) { mutableStateOf("") }
        val filterQuery by filterQueryState
    val visibleDirectories = remember(state.contentDirectories, filterQuery, live.sortMode) {
        ExplorerListOrdering.apply(state.contentDirectories, filterQuery, live.sortMode)
    }
    val visibleFiles = remember(state.contentFiles, filterQuery, live.sortMode) {
        ExplorerListOrdering.apply(state.contentFiles, filterQuery, live.sortMode)
    }
    val openActionLabel = stringRes("open")
    val showInFolderLabel = stringRes("show_in_folder")
    val ownedFocus = paneFocus ?: remember { ExplorerPaneFocus() }
    val jadedOrbital = LocalAppTheme.current.traits.orbitalHome &&
        LocalKineticStyle.current == KineticStyle.JADED_STEEL
        val frame = ExplorerFrame(
            state, toolsAvailable, showCopyFabs, topBarTitle, paneFocus, splitOpen, folderPane, backEnabled, visibleDirectories, visibleFiles,
            openActionLabel, showInFolderLabel, ownedFocus, jadedOrbital, filterQueryState
        )
        return ExplorerScope(inputs, live, frame)
    }
}

internal object ExplorerSplitPolicy {
    fun isSplitOpen(
        hostSplit: Boolean,
        viewMode: ExplorerViewMode,
        target: BrowseTarget
    ): Boolean = hostSplit &&
        viewMode == ExplorerViewMode.Split &&
        (target is BrowseTarget.Local || target is BrowseTarget.Remote || target is BrowseTarget.Demo)

    fun defaultSecondaryLocalTarget(): BrowseTarget.Local {
        val identity = FileApexServices.localIdentity
        return BrowseTarget.Local(
            deviceId = LocalIdentity.LOCAL_DEVICE_ID,
            displayName = AppI18n.t("this_device_named", LocalDeviceNameStore.current()),
            rootPath = identity.rootPath
        )
    }
}
