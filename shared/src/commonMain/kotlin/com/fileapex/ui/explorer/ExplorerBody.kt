package com.fileapex.ui.explorer

import com.fileapex.ui.FileExplorerScreen
import com.fileapex.ui.activePaneGlow
import com.fileapex.ui.LOCAL_SECONDARY_VM_KEY
import com.fileapex.ui.ExplorerNavigationAction
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.domain.demo.DemoModeState
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.ExplorerActionCopy
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.AdaptiveExplorerView
import com.fileapex.ui.DemoModeBanner
import com.fileapex.ui.ExplorerBreadcrumb
import com.fileapex.ui.ExplorerPaneId
import com.fileapex.ui.LocalExplorerPaneFocus
import com.fileapex.ui.PaneSourceLabel
import com.fileapex.ui.SecondarySourcePicker
import com.fileapex.ui.StorageToolsScreen
import com.fileapex.ui.adaptive.CompactHomeTitleBand
import com.fileapex.ui.adaptive.CompactHomeTitleStyle
import com.fileapex.ui.dnd.ExplorerDropHighlight
import com.fileapex.ui.dnd.LocalExplorerDropHighlight
import com.fileapex.ui.dnd.localFolderDropTarget
import com.fileapex.ui.theme.KineticStyleLook

@Composable
internal fun ExplorerScope.ExplorerBody(dropHighlight: ExplorerDropHighlight, openFolder: String, acceptDrop: (List<String>) -> Unit) {
    with(inputs) { with(frame) { with(live) {
        if (toolsOpen && toolsAvailable) {
            StorageToolsScreen(
                onCopyItem = viewModel::copyItem,
                onSendItem = viewModel::sendItemToDevices,
                onClose = { toolsOpen = false },
                onOpenFolder = { path ->
                    toolsOpen = false
                    viewModel.openPath(path)
                }
            )
        } else {
        CompositionLocalProvider(LocalExplorerDropHighlight provides dropHighlight) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .localFolderDropTarget(
                    enabled = !splitOpen,
                    onHoverChanged = { active -> if (!active) dropHighlight.clearHover() },
                    onDragMove = { position -> dropHighlight.hoverAt(position, openFolder) },
                    onDragEnded = { dropHighlight.clear() },
                    onFiles = acceptDrop
                )
                .pointerInput(paneId, ownedFocus, splitOpen) {
                    if (splitOpen && paneId == ExplorerPaneId.Primary) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press) {
                                ownedFocus.active = paneId
                            }
                        }
                    }
                }
        ) {
            when {
                state.isLoading &&
                    state.paneDirectories.isEmpty() &&
                    state.paneFiles.isEmpty() &&
                    state.contentDirectories.isEmpty() &&
                    state.contentFiles.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        ExplorerHeader()
                        val breadcrumbLabel = when {
                            splitOpen && paneId == ExplorerPaneId.Primary -> stringRes("pane_local")
                            !paneLabel.isNullOrBlank() -> paneLabel
                            else -> null
                        }
                        ExplorerPanes(this@ExplorerBody, breadcrumbLabel, dropHighlight, openFolder, acceptDrop)
                    }
                }
            }
            ExplorerBodyOverlays()
        }
        }
        }
    } } }
}


@Composable
internal fun ExplorerScope.ExplorerHeader() {
    with(inputs) { with(frame) { with(live) {
        if (embeddedInCompactShell) {
            CompactHomeTitleBand(
                primaryLine = "FileApex",
                secondaryLine = null,
                style = CompactHomeTitleStyle.Detail,
                onOpenTransferQueue = onOpenTransferQueue,
                showCloseService = onExitApp != null,
                onCloseService = onExitApp,
                actions = {
                    ExplorerNavigationAction(
                        state = state,
                        embeddedInCompactShell = true,
                        onNavigate = {
                            if (!viewModel.handleBackNavigation()) {
                                onBack()
                            }
                        },
                        onBack = onBack
                    )
                    ExplorerTopBarActions(
                        state = state,
                        embeddedInCompactShell = true,
                        viewModel = viewModel,
                        onOpenTools = if (toolsAvailable) ({ toolsOpen = true }) else null
                    )
                }
            )
        }
        if (target is BrowseTarget.Demo) {
            DemoModeBanner(
                onExitDemo = {
                    DemoModeState.exitDemo()
                    onBack()
                }
            )
        }
        if (state.isRemoteTarget && (state.isLoading || state.isRefreshing)) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = if (jadedOrbital) KineticStyleLook.steel else MaterialTheme.colorScheme.primary,
                trackColor = if (jadedOrbital) KineticStyleLook.steel.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            )
        } else {
            Spacer(modifier = Modifier.height(3.dp))
        }
        if (state.isSelectionMode && state.selectedFileIds.isNotEmpty()) {
            Text(
                text = ExplorerActionCopy.SELECTION_MODE_HELPER,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.canPaste && !state.isSelectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringRes("ready_to_paste", state.clipboardLabel ?: stringRes("file_s")),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (jadedOrbital) KineticStyleLook.steel else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = viewModel::pasteHere) {
                    Text(
                        text = stringRes("paste_here"),
                        color = if (jadedOrbital) KineticStyleLook.steel else Color.Unspecified
                    )
                }
                IconButton(onClick = viewModel::clearPendingCopy) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringRes("clear_pending_copy"),
                        tint = if (jadedOrbital) KineticStyleLook.steel else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    } } }
}


@Composable
internal fun ColumnScope.ExplorerPanes(
    notes: ExplorerScope,
    breadcrumbLabel: String?,
    dropHighlight: ExplorerDropHighlight,
    openFolder: String,
    acceptDrop: (List<String>) -> Unit
) {
    with(notes) { with(inputs) { with(frame) { with(live) {
        if (splitOpen) {
            val secondaryLocal = remember(target.deviceId, target.rootPath, target.displayName) {
                BrowseTarget.Local(
                    deviceId = target.deviceId,
                    displayName = target.displayName,
                    rootPath = target.rootPath
                )
            }
            val rightTarget = secondaryTarget ?: secondaryLocal
            val rightKey = if (rightTarget is BrowseTarget.Remote) {
                rightTarget.deviceId
            } else {
            LOCAL_SECONDARY_VM_KEY
            }
            val focus = LocalExplorerPaneFocus.current
            val glow = if (jadedOrbital) KineticStyleLook.steel else MaterialTheme.colorScheme.primary
            val leftActive = focus == null || focus.active == ExplorerPaneId.Primary
            val rightActive = focus?.active == ExplorerPaneId.Secondary
            @Composable
            fun LeftPane(modifier: Modifier) {
                Column(
                    modifier = modifier
                        .activePaneGlow(leftActive, glow)
                        .localFolderDropTarget(
                            enabled = true,
                            onHoverChanged = { active -> if (!active) dropHighlight.clearHover() },
                            onDragMove = { position -> dropHighlight.hoverAt(position, openFolder) },
                            onDragEnded = { dropHighlight.clear() },
                            onFiles = acceptDrop
                        )
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press) {
                                        focus?.active = ExplorerPaneId.Primary
                                    }
                                }
                            }
                        }
                ) {
                    PaneSourceLabel(stringRes("pane_local"))
                    Spacer(modifier = Modifier.height(3.dp))
                    ExplorerBreadcrumb(
                        label = null,
                        path = state.currentPath,
                        rootPath = viewModel.browseRootPath(),
                        onJump = viewModel::openPath,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    ExplorerFilterBar(
                        query = filterQuery,
                        onQueryChange = { filterQuery = it },
                        sortMode = sortMode,
                        onSortModeChange = { sortMode = it }
                    )
                    AdaptiveExplorerView(
                        isWideDisplay = false,
                        viewMode = ExplorerViewMode.List,
                        panePath = state.panePath,
                        paneDirectories = state.paneDirectories,
                        contentDirectories = visibleDirectories,
                        contentFiles = visibleFiles,
                        selectedFolderPath = state.selectedFolderPath,
                        canNavigateUp = state.canNavigateUp,
                        isSelectionMode = state.isSelectionMode,
                        selectedFileIds = state.selectedFileIds,
                        isRemoteTarget = false,
                        sourceDeviceId = null,
                        loadingFolderPath = null,
                        isLoading = false,
                        onNavigateUp = viewModel::navigateUp,
                        onPaneFolderClick = viewModel::onPaneFolderClick,
                        onContentDirectoryClick = viewModel::onContentDirectoryClick,
                        onFileOpen = viewModel::onFileClick,
                        onFileLongPress = viewModel::onFileLongClick,
                        onPreviewFirstSplitPaneFolder = viewModel::previewFirstSplitPaneFolder,
                        onFileSelectExclusive = viewModel::selectFileExclusive,
                        onFileToggleSelect = viewModel::toggleFileSelectionDesktop,
                        onFileExtendSelect = viewModel::extendFileSelection,
                        onFileActivate = viewModel::activateFile,
                        onCopyItem = viewModel::copyItem,
                        onSendItemToDevice = viewModel::sendItemToDevices,
                        onDownloadItem = viewModel::downloadItem,
                        contentBottomPadding = if (showCopyFabs) 140.dp else 24.dp,
                        contentDropPath = state.currentPath,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
            @Composable
            fun RightPane(modifier: Modifier) {
                Column(
                    modifier = modifier
                        .activePaneGlow(rightActive, glow)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press) {
                                        focus?.active = ExplorerPaneId.Secondary
                                    }
                                }
                            }
                        }
                ) {
                    SecondarySourcePicker(
                        secondaryTarget = secondaryTarget,
                        readyDevices = readyDevices,
                        onSelectLocal = onSelectSecondaryLocal,
                        onSelectDevice = onSelectSecondaryDevice
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        FileExplorerScreen(
                            target = rightTarget,
                            onBack = {},
                            embeddedInCompactShell = false,
                            paneId = ExplorerPaneId.Secondary,
                            hostSplit = false,
                            layoutExpanded = false,
                            showTopBar = false,
                            paneLabel = null,
                            viewModelKey = rightKey
                        )
                    }
                }
            }
            if (layoutExpanded) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LeftPane(Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider()
                    RightPane(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LeftPane(Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider()
                    RightPane(Modifier.weight(1f).fillMaxWidth())
                }
            }
        } else {
            ExplorerSingleFolderView(notes, breadcrumbLabel)
        }
    } } } }
}


@Composable
internal fun ExplorerScope.ExplorerBodyOverlays() {
    with(inputs) { with(frame) { with(live) {
        if (state.isRemoteTarget && state.isLoading && (state.paneDirectories.isNotEmpty() || state.contentDirectories.isNotEmpty() || state.contentFiles.isNotEmpty())) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (showCopyFabs) 140.dp else 24.dp, end = 20.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                    shadowElevation = 8.dp,
                    border = BorderStroke(
                        1.dp,
                        (if (jadedOrbital) KineticStyleLook.steel else MaterialTheme.colorScheme.primary).copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = if (jadedOrbital) KineticStyleLook.steel else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringRes("opening_folder"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (state.isMultiCopying) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(ExplorerActionCopy.SEND_TO_IN_PROGRESS)
                }
            }
        }
    } } }
}



@Composable
internal fun ColumnScope.ExplorerSingleFolderView(notes: ExplorerScope, breadcrumbLabel: String?) {
    with(notes) { with(inputs) { with(frame) { with(live) {
        ExplorerBreadcrumb(
            label = breadcrumbLabel,
            path = state.currentPath,
            rootPath = viewModel.browseRootPath(),
            onJump = viewModel::openPath,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        ExplorerFilterBar(
            query = filterQuery,
            onQueryChange = { filterQuery = it },
            sortMode = sortMode,
            onSortModeChange = { sortMode = it },
            leading = filterBarLeading
        )
        AdaptiveExplorerView(
            isWideDisplay = folderPane,
            viewMode = if (state.viewMode == ExplorerViewMode.Split) ExplorerViewMode.List else state.viewMode,
            panePath = state.panePath,
            paneDirectories = state.paneDirectories,
            contentDirectories = visibleDirectories,
            contentFiles = visibleFiles,
            selectedFolderPath = state.selectedFolderPath,
            canNavigateUp = state.canNavigateUp,
            isSelectionMode = state.isSelectionMode,
            selectedFileIds = state.selectedFileIds,
            isRemoteTarget = state.isRemoteTarget,
            sourceDeviceId = state.sourceDeviceId,
            loadingFolderPath = state.loadingFolderPath,
            isLoading = state.isLoading,
            onNavigateUp = viewModel::navigateUp,
            onPaneFolderClick = viewModel::onPaneFolderClick,
            onContentDirectoryClick = viewModel::onContentDirectoryClick,
            onFileOpen = viewModel::onFileClick,
            onFileLongPress = viewModel::onFileLongClick,
            onPreviewFirstSplitPaneFolder = viewModel::previewFirstSplitPaneFolder,
            onFileSelectExclusive = viewModel::selectFileExclusive,
            onFileToggleSelect = viewModel::toggleFileSelectionDesktop,
            onFileExtendSelect = viewModel::extendFileSelection,
            onFileActivate = viewModel::activateFile,
            onCopyItem = viewModel::copyItem,
            onSendItemToDevice = viewModel::sendItemToDevices,
            onDownloadItem = viewModel::downloadItem,
            contentBottomPadding = if (showCopyFabs) 140.dp else 24.dp,
            contentDropPath = state.currentPath,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
    } } } }
}
