package com.fileapex.ui

import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.i18n.stringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.filled.Folder
import com.fileapex.ui.dnd.deviceFileDragSource



import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.theme.FileApexTeal
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phone: single list or grid of folders + files with ".." at top.
 * Wide/fold: left = folder list; right = list or grid for the selected folder contents.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AdaptiveExplorerView(
    isWideDisplay: Boolean,
    viewMode: ExplorerViewMode,
    panePath: String,
    paneDirectories: List<RemoteFileItem>,
    contentDirectories: List<RemoteFileItem>,
    contentFiles: List<RemoteFileItem>,
    selectedFolderPath: String?,
    canNavigateUp: Boolean,
    isSelectionMode: Boolean,
    selectedFileIds: Set<String>,
    isRemoteTarget: Boolean = false,
    sourceDeviceId: String? = null,
    loadingFolderPath: String? = null,
    isLoading: Boolean = false,
    onNavigateUp: () -> Unit,
    onPaneFolderClick: (RemoteFileItem) -> Unit,
    onContentDirectoryClick: (RemoteFileItem) -> Unit,
    onFileOpen: (RemoteFileItem) -> Unit,
    onFileLongPress: (RemoteFileItem) -> Unit,
    onFileSelectExclusive: (RemoteFileItem) -> Unit = {},
    onFileToggleSelect: (RemoteFileItem) -> Unit = {},
    onFileExtendSelect: (RemoteFileItem) -> Unit = {},
    onFileActivate: (RemoteFileItem) -> Unit = {},
    onCopyItem: (RemoteFileItem) -> Unit = {},
    onSendItemToDevice: (RemoteFileItem) -> Unit = {},
    onDownloadItem: (RemoteFileItem) -> Unit = {},
    onPreviewFirstSplitPaneFolder: () -> Unit = {},
    modifier: Modifier = Modifier,
    contentBottomPadding: Dp = 24.dp
) {
    val listPadding = PaddingValues(bottom = contentBottomPadding)
    val showingPaneRootFiles = selectedFolderPath == null
    val desktopSelection = usesDesktopFileSelection()

    LaunchedEffect(isWideDisplay, panePath, selectedFolderPath) {
        if (isWideDisplay && selectedFolderPath == null && paneDirectories.isNotEmpty()) {
            onPreviewFirstSplitPaneFolder()
        }
    }

    if (isWideDisplay) {
        val rightDirs = if (showingPaneRootFiles) emptyList() else contentDirectories
        val rightFiles = contentFiles
        val rightEmpty = rightDirs.isEmpty() && rightFiles.isEmpty()

        Row(modifier = modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                contentPadding = listPadding
            ) {
                if (canNavigateUp) {
                    item(key = "pane-parent") {
                        ParentRow(
                            onClick = onNavigateUp,
                            isLoading = isLoading && loadingFolderPath != null && pathsEqual(panePath, loadingFolderPath)
                        )
                    }
                }
                items(paneDirectories, key = { "pane-${it.id}" }) { dir ->
                    val selected = selectedFolderPath != null &&
                        pathsEqual(dir.absolutePath, selectedFolderPath)
                    val isChecked = dir.id in selectedFileIds
                    PaneDirectoryRow(
                        dir = dir,
                        isSelectedInPane = selected,
                        isLoading = isLoading,
                        loadingFolderPath = loadingFolderPath,
                        isSelectionMode = isSelectionMode,
                        isChecked = isChecked,
                        desktopSelection = desktopSelection,
                        isRemoteTarget = isRemoteTarget,
                        onClick = { onPaneFolderClick(dir) },
                        onLongClick = { onFileLongPress(dir) },
                        onSelectExclusive = { onFileSelectExclusive(dir) },
                        onToggleSelect = { onFileToggleSelect(dir) },
                        onExtendSelect = { onFileExtendSelect(dir) },
                        onActivate = { onPaneFolderClick(dir) },
                        onCopy = { onCopyItem(dir) },
                        onSendToDevice = { onSendItemToDevice(dir) },
                        onDownload = { onDownloadItem(dir) }
                    )
                }
                if (paneDirectories.isEmpty() && !canNavigateUp) {
                    item(key = "pane-empty") {
                        EmptyHint(stringRes("no_folders"))
                    }
                }
            }
            Spacer(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            ExplorerContentPane(
                viewMode = viewMode,
                canNavigateUp = canNavigateUp,
                directories = rightDirs,
                files = rightFiles,
                isEmpty = rightEmpty,
                emptyHint = if (showingPaneRootFiles) {
                    stringRes("select_folder_or_browse")
                } else {
                    stringRes("folder_empty")
                },
                isSelectionMode = isSelectionMode,
                selectedFileIds = selectedFileIds,
                desktopSelection = desktopSelection,
                isRemoteTarget = isRemoteTarget,
                sourceDeviceId = sourceDeviceId,
                isLoading = isLoading,
                loadingFolderPath = loadingFolderPath,
                listPadding = listPadding,
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight(),
                onNavigateUp = onNavigateUp,
                onDirectoryClick = onContentDirectoryClick,
                onFileOpen = onFileOpen,
                onFileLongPress = onFileLongPress,
                onFileSelectExclusive = onFileSelectExclusive,
                onFileToggleSelect = onFileToggleSelect,
                onFileExtendSelect = onFileExtendSelect,
                onFileActivate = onFileActivate,
                onCopyItem = onCopyItem,
                onSendItemToDevice = onSendItemToDevice,
                onDownloadItem = onDownloadItem
            )
        }
        return
    }

    val empty = contentDirectories.isEmpty() && contentFiles.isEmpty()
    if (empty && !canNavigateUp) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringRes("folder_empty"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    ExplorerContentPane(
        viewMode = viewMode,
        canNavigateUp = canNavigateUp,
        directories = contentDirectories,
        files = contentFiles,
        isEmpty = false,
        emptyHint = stringRes("folder_empty"),
        isSelectionMode = isSelectionMode,
        selectedFileIds = selectedFileIds,
        desktopSelection = desktopSelection,
        isRemoteTarget = isRemoteTarget,
        sourceDeviceId = sourceDeviceId,
        isLoading = isLoading,
        loadingFolderPath = loadingFolderPath,
        listPadding = listPadding,
        modifier = modifier.fillMaxSize(),
        onNavigateUp = onNavigateUp,
        onDirectoryClick = onContentDirectoryClick,
        onFileOpen = onFileOpen,
        onFileLongPress = onFileLongPress,
        onFileSelectExclusive = onFileSelectExclusive,
        onFileToggleSelect = onFileToggleSelect,
        onFileExtendSelect = onFileExtendSelect,
        onFileActivate = onFileActivate,
        onCopyItem = onCopyItem,
        onSendItemToDevice = onSendItemToDevice,
        onDownloadItem = onDownloadItem
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExplorerContentPane(
    viewMode: ExplorerViewMode,
    canNavigateUp: Boolean,
    directories: List<RemoteFileItem>,
    files: List<RemoteFileItem>,
    isEmpty: Boolean,
    emptyHint: String,
    isSelectionMode: Boolean,
    selectedFileIds: Set<String>,
    desktopSelection: Boolean,
    isRemoteTarget: Boolean,
    sourceDeviceId: String? = null,
    isLoading: Boolean = false,
    loadingFolderPath: String? = null,
    listPadding: PaddingValues,
    modifier: Modifier,
    onNavigateUp: () -> Unit,
    onDirectoryClick: (RemoteFileItem) -> Unit,
    onFileOpen: (RemoteFileItem) -> Unit,
    onFileLongPress: (RemoteFileItem) -> Unit,
    onFileSelectExclusive: (RemoteFileItem) -> Unit,
    onFileToggleSelect: (RemoteFileItem) -> Unit,
    onFileExtendSelect: (RemoteFileItem) -> Unit,
    onFileActivate: (RemoteFileItem) -> Unit,
    onCopyItem: (RemoteFileItem) -> Unit,
    onSendItemToDevice: (RemoteFileItem) -> Unit,
    onDownloadItem: (RemoteFileItem) -> Unit
) {
    when (viewMode) {
        ExplorerViewMode.List -> ExplorerListContent(
            canNavigateUp = canNavigateUp,
            directories = directories,
            files = files,
            isEmpty = isEmpty,
            emptyHint = emptyHint,
            isSelectionMode = isSelectionMode,
            selectedFileIds = selectedFileIds,
            desktopSelection = desktopSelection,
            isRemoteTarget = isRemoteTarget,
            sourceDeviceId = sourceDeviceId,
            isLoading = isLoading,
            loadingFolderPath = loadingFolderPath,
            listPadding = listPadding,
            modifier = modifier,
            onNavigateUp = onNavigateUp,
            onDirectoryClick = onDirectoryClick,
            onFileOpen = onFileOpen,
            onFileLongPress = onFileLongPress,
            onFileSelectExclusive = onFileSelectExclusive,
            onFileToggleSelect = onFileToggleSelect,
            onFileExtendSelect = onFileExtendSelect,
            onFileActivate = onFileActivate,
            onCopyItem = onCopyItem,
            onSendItemToDevice = onSendItemToDevice,
            onDownloadItem = onDownloadItem
        )
        ExplorerViewMode.Grid -> ExplorerGridContent(
            canNavigateUp = canNavigateUp,
            directories = directories,
            files = files,
            isEmpty = isEmpty,
            emptyHint = emptyHint,
            isSelectionMode = isSelectionMode,
            selectedFileIds = selectedFileIds,
            desktopSelection = desktopSelection,
            isRemoteTarget = isRemoteTarget,
            sourceDeviceId = sourceDeviceId,
            isLoading = isLoading,
            loadingFolderPath = loadingFolderPath,
            listPadding = listPadding,
            modifier = modifier,
            onNavigateUp = onNavigateUp,
            onDirectoryClick = onDirectoryClick,
            onFileOpen = onFileOpen,
            onFileLongPress = onFileLongPress,
            onFileSelectExclusive = onFileSelectExclusive,
            onFileToggleSelect = onFileToggleSelect,
            onFileExtendSelect = onFileExtendSelect,
            onFileActivate = onFileActivate,
            onCopyItem = onCopyItem,
            onSendItemToDevice = onSendItemToDevice,
            onDownloadItem = onDownloadItem
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExplorerListContent(
    canNavigateUp: Boolean,
    directories: List<RemoteFileItem>,
    files: List<RemoteFileItem>,
    isEmpty: Boolean,
    emptyHint: String,
    isSelectionMode: Boolean,
    selectedFileIds: Set<String>,
    desktopSelection: Boolean,
    isRemoteTarget: Boolean,
    sourceDeviceId: String? = null,
    isLoading: Boolean = false,
    loadingFolderPath: String? = null,
    listPadding: PaddingValues,
    modifier: Modifier,
    onNavigateUp: () -> Unit,
    onDirectoryClick: (RemoteFileItem) -> Unit,
    onFileOpen: (RemoteFileItem) -> Unit,
    onFileLongPress: (RemoteFileItem) -> Unit,
    onFileSelectExclusive: (RemoteFileItem) -> Unit,
    onFileToggleSelect: (RemoteFileItem) -> Unit,
    onFileExtendSelect: (RemoteFileItem) -> Unit,
    onFileActivate: (RemoteFileItem) -> Unit,
    onCopyItem: (RemoteFileItem) -> Unit,
    onSendItemToDevice: (RemoteFileItem) -> Unit,
    onDownloadItem: (RemoteFileItem) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = listPadding
    ) {
        if (canNavigateUp) {
            item(key = "parent") {
                ParentRow(
                    onClick = onNavigateUp,
                    isLoading = isLoading && loadingFolderPath != null && !directories.any { pathsEqual(it.absolutePath, loadingFolderPath) }
                )
            }
        }
        if (isEmpty) {
            item(key = "content-empty") {
                EmptyHint(emptyHint)
            }
        }
        items(directories, key = { "dir-${it.id}" }) { dir ->
            DirectoryListRow(
                dir = dir,
                isLoading = isLoading,
                loadingFolderPath = loadingFolderPath,
                isSelectionMode = isSelectionMode,
                isSelected = dir.id in selectedFileIds,
                desktopSelection = desktopSelection,
                isRemoteTarget = isRemoteTarget,
                sourceDeviceId = sourceDeviceId,
                onClick = { onDirectoryClick(dir) },
                onLongClick = { onFileLongPress(dir) },
                onSelectExclusive = { onFileSelectExclusive(dir) },
                onToggleSelect = { onFileToggleSelect(dir) },
                onExtendSelect = { onFileExtendSelect(dir) },
                onActivate = { onDirectoryClick(dir) },
                onCopy = { onCopyItem(dir) },
                onSendToDevice = { onSendItemToDevice(dir) },
                onDownload = { onDownloadItem(dir) }
            )
        }
        items(files, key = { "file-${it.id}" }) { file ->
            FileListRow(
                file = file,
                isSelectionMode = isSelectionMode,
                isSelected = file.id in selectedFileIds,
                desktopSelection = desktopSelection,
                isRemoteTarget = isRemoteTarget,
                sourceDeviceId = sourceDeviceId,
                onClick = { onFileOpen(file) },
                onLongClick = { onFileLongPress(file) },
                onSelectExclusive = { onFileSelectExclusive(file) },
                onToggleSelect = { onFileToggleSelect(file) },
                onExtendSelect = { onFileExtendSelect(file) },
                onActivate = { onFileActivate(file) },
                onCopy = { onCopyItem(file) },
                onSendToDevice = { onSendItemToDevice(file) },
                onDownload = { onDownloadItem(file) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExplorerGridContent(
    canNavigateUp: Boolean,
    directories: List<RemoteFileItem>,
    files: List<RemoteFileItem>,
    isEmpty: Boolean,
    emptyHint: String,
    isSelectionMode: Boolean,
    selectedFileIds: Set<String>,
    desktopSelection: Boolean,
    isRemoteTarget: Boolean,
    sourceDeviceId: String? = null,
    isLoading: Boolean = false,
    loadingFolderPath: String? = null,
    listPadding: PaddingValues,
    modifier: Modifier,
    onNavigateUp: () -> Unit,
    onDirectoryClick: (RemoteFileItem) -> Unit,
    onFileOpen: (RemoteFileItem) -> Unit,
    onFileLongPress: (RemoteFileItem) -> Unit,
    onFileSelectExclusive: (RemoteFileItem) -> Unit,
    onFileToggleSelect: (RemoteFileItem) -> Unit,
    onFileExtendSelect: (RemoteFileItem) -> Unit,
    onFileActivate: (RemoteFileItem) -> Unit,
    onCopyItem: (RemoteFileItem) -> Unit,
    onSendItemToDevice: (RemoteFileItem) -> Unit,
    onDownloadItem: (RemoteFileItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = modifier,
        contentPadding = listPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (canNavigateUp) {
            item(key = "parent", span = { GridItemSpan(maxLineSpan) }) {
                ParentRow(
                    onClick = onNavigateUp,
                    isLoading = isLoading && loadingFolderPath != null && !directories.any { pathsEqual(it.absolutePath, loadingFolderPath) }
                )
            }
        }
        if (isEmpty) {
            item(key = "content-empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyHint(emptyHint)
            }
        }
        items(directories, key = { "gdir-${it.id}" }) { dir ->
            ExplorerGridCell(
                item = dir,
                subtitle = com.fileapex.i18n.AppI18n.t("folder"),
                isLoading = isLoading,
                loadingFolderPath = loadingFolderPath,
                isSelectionMode = isSelectionMode,
                isSelected = dir.id in selectedFileIds,
                desktopSelection = desktopSelection,
                isRemoteTarget = isRemoteTarget,
                sourceDeviceId = sourceDeviceId,
                onClick = { onDirectoryClick(dir) },
                onLongClick = { onFileLongPress(dir) },
                onSelectExclusive = { onFileSelectExclusive(dir) },
                onToggleSelect = { onFileToggleSelect(dir) },
                onExtendSelect = { onFileExtendSelect(dir) },
                onActivate = { onDirectoryClick(dir) },
                onCopy = { onCopyItem(dir) },
                onSendToDevice = { onSendItemToDevice(dir) },
                onDownload = { onDownloadItem(dir) }
            )
        }
        items(files, key = { "gfile-${it.id}" }) { file ->
            ExplorerGridCell(
                item = file,
                subtitle = formatBytes(file.sizeBytes),
                isSelectionMode = isSelectionMode,
                isSelected = file.id in selectedFileIds,
                desktopSelection = desktopSelection,
                isRemoteTarget = isRemoteTarget,
                sourceDeviceId = sourceDeviceId,
                onClick = { onFileOpen(file) },
                onLongClick = { onFileLongPress(file) },
                onSelectExclusive = { onFileSelectExclusive(file) },
                onToggleSelect = { onFileToggleSelect(file) },
                onExtendSelect = { onFileExtendSelect(file) },
                onActivate = { onFileActivate(file) },
                onCopy = { onCopyItem(file) },
                onSendToDevice = { onSendItemToDevice(file) },
                onDownload = { onDownloadItem(file) }
            )
        }
    }
}

@Composable
private fun ParentRow(onClick: () -> Unit, isLoading: Boolean = false) {
    val isFluxGlass = LocalAppTheme.current == AppTheme.FLUX_GLASS
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = if (isFluxGlass) Color(0xFF00E676) else MaterialTheme.colorScheme.primary
                )
            }
        } else {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = stringRes("up"),
                tint = if (isFluxGlass) Color(0xFF00E676) else FileApexTeal,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "..",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (isFluxGlass) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = stringRes("up_one_folder"),
                style = MaterialTheme.typography.bodySmall,
                color = if (isFluxGlass) Color(0xFFCBD5E1) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider(color = if (isFluxGlass) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun EmptyHint(text: String) {
    val isFluxGlass = LocalAppTheme.current == AppTheme.FLUX_GLASS
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (isFluxGlass) Color(0xFFCBD5E1) else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneDirectoryRow(
    dir: RemoteFileItem,
    isSelectedInPane: Boolean,
    isLoading: Boolean = false,
    loadingFolderPath: String? = null,
    isSelectionMode: Boolean,
    isChecked: Boolean,
    desktopSelection: Boolean,
    isRemoteTarget: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectExclusive: () -> Unit,
    onToggleSelect: () -> Unit,
    onExtendSelect: () -> Unit,
    onActivate: () -> Unit,
    onCopy: () -> Unit,
    onSendToDevice: () -> Unit,
    onDownload: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isFluxGlass = LocalAppTheme.current == AppTheme.FLUX_GLASS
    val rowModifier = explorerItemRowModifier(
        desktopSelection = desktopSelection,
        isSelectionMode = isSelectionMode,
        onClick = onClick,
        onLongClick = onLongClick,
        onToggleSelect = onToggleSelect,
        onExtendSelect = onExtendSelect,
        onActivate = onActivate,
        onSecondaryClick = { menuExpanded = true }
    )

    Box {
        Row(
            modifier = rowModifier
                .background(
                    if (isFluxGlass) {
                        if (isChecked) Color(0x4400E676)
                        else if (isSelectedInPane) Color(0x4400E676)
                        else Color.Transparent
                    } else {
                        if (isChecked) FileApexTeal.copy(alpha = 0.14f)
                        else if (isSelectedInPane) FileApexTeal.copy(alpha = 0.14f)
                        else Color.Transparent
                    }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                SelectionIndicator(selected = isChecked)
                Spacer(modifier = Modifier.width(12.dp))
            }
            val isItemLoading = isLoading && loadingFolderPath != null && pathsEqual(dir.absolutePath, loadingFolderPath)
            if (isItemLoading) {
                Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = if (isFluxGlass) Color(0xFF00E676) else MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                ExplorerEntryIcon(item = dir, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = dir.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isSelectedInPane || isChecked) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = if (isFluxGlass) Color.White else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = com.fileapex.i18n.AppI18n.t("folder"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isFluxGlass) Color(0xFFCBD5E1) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        ItemContextMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            isRemoteTarget = isRemoteTarget,
            onCopy = onCopy,
            onSendToDevice = onSendToDevice,
            onDownload = onDownload
        )
    }
    HorizontalDivider(color = if (isFluxGlass) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DirectoryListRow(
    dir: RemoteFileItem,
    isLoading: Boolean = false,
    loadingFolderPath: String? = null,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    desktopSelection: Boolean,
    isRemoteTarget: Boolean,
    sourceDeviceId: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectExclusive: () -> Unit,
    onToggleSelect: () -> Unit,
    onExtendSelect: () -> Unit,
    onActivate: () -> Unit,
    onCopy: () -> Unit,
    onSendToDevice: () -> Unit,
    onDownload: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isFluxGlass = LocalAppTheme.current == AppTheme.FLUX_GLASS
    val rowModifier = explorerItemRowModifier(
        desktopSelection = desktopSelection,
        isSelectionMode = isSelectionMode,
        onClick = onClick,
        onLongClick = onLongClick,
        onToggleSelect = onToggleSelect,
        onExtendSelect = onExtendSelect,
        onActivate = onActivate,
        onSecondaryClick = { menuExpanded = true }
    )

    val dragModifier = if (dir.absolutePath.isNotBlank()) {
        Modifier.deviceFileDragSource(
            absolutePath = dir.absolutePath,
            sourceDeviceId = if (isRemoteTarget) sourceDeviceId else null,
            fileName = dir.name,
            fileSize = 0L
        )
    } else Modifier

    Box(modifier = Modifier.then(dragModifier)) {
        Row(
            modifier = rowModifier
                .background(
                    if (isFluxGlass) {
                        if (isSelected) Color(0x4400E676) else Color.Transparent
                    } else {
                        if (isSelected) FileApexTeal.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface
                    }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                SelectionIndicator(selected = isSelected)
                Spacer(modifier = Modifier.width(12.dp))
            }
            val isItemLoading = isLoading && loadingFolderPath != null && pathsEqual(dir.absolutePath, loadingFolderPath)
            if (isItemLoading) {
                Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = if (isFluxGlass) Color(0xFF00E676) else MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                ExplorerEntryIcon(item = dir, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = dir.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = com.fileapex.i18n.AppI18n.t("folder"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        ItemContextMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            isRemoteTarget = isRemoteTarget,
            onCopy = onCopy,
            onSendToDevice = onSendToDevice,
            onDownload = onDownload
        )
    }
    HorizontalDivider(color = if (isFluxGlass) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListRow(
    file: RemoteFileItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    desktopSelection: Boolean,
    isRemoteTarget: Boolean,
    sourceDeviceId: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectExclusive: () -> Unit,
    onToggleSelect: () -> Unit,
    onExtendSelect: () -> Unit,
    onActivate: () -> Unit,
    onCopy: () -> Unit,
    onSendToDevice: () -> Unit,
    onDownload: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isFluxGlass = LocalAppTheme.current == AppTheme.FLUX_GLASS
    val rowModifier = explorerItemRowModifier(
        desktopSelection = desktopSelection,
        isSelectionMode = isSelectionMode,
        onClick = onClick,
        onLongClick = onLongClick,
        onToggleSelect = onToggleSelect,
        onExtendSelect = onExtendSelect,
        onActivate = onActivate,
        onSecondaryClick = { menuExpanded = true }
    )

    val dragModifier = if (file.absolutePath.isNotBlank()) {
        Modifier.deviceFileDragSource(
            absolutePath = file.absolutePath,
            sourceDeviceId = if (isRemoteTarget) sourceDeviceId else null,
            fileName = file.name,
            fileSize = file.sizeBytes
        )
    } else Modifier

    Box(modifier = Modifier.then(dragModifier)) {
        Row(
            modifier = rowModifier
                .background(
                    if (isFluxGlass) {
                        if (isSelected) Color(0x4400E676) else Color.Transparent
                    } else {
                        if (isSelected) FileApexTeal.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface
                    }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                SelectionIndicator(selected = isSelected)
                Spacer(modifier = Modifier.width(12.dp))
            }
            ExplorerEntryIcon(item = file, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatBytes(file.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        ItemContextMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            isRemoteTarget = isRemoteTarget,
            onCopy = onCopy,
            onSendToDevice = onSendToDevice,
            onDownload = onDownload
        )
    }
    HorizontalDivider(color = if (isFluxGlass) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExplorerGridCell(
    item: RemoteFileItem,
    subtitle: String,
    isLoading: Boolean = false,
    loadingFolderPath: String? = null,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    desktopSelection: Boolean,
    isRemoteTarget: Boolean,
    sourceDeviceId: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectExclusive: () -> Unit,
    onToggleSelect: () -> Unit,
    onExtendSelect: () -> Unit,
    onActivate: () -> Unit,
    onCopy: () -> Unit,
    onSendToDevice: () -> Unit,
    onDownload: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isFluxGlass = LocalAppTheme.current == AppTheme.FLUX_GLASS
    val interactionModifier = if (desktopSelection) {
        Modifier.desktopItemClicks(
            isSelectionMode = isSelectionMode,
            onClick = onClick,
            onToggleSelect = onToggleSelect,
            onExtendSelect = onExtendSelect,
            onActivate = onActivate,
            onSecondaryClick = { menuExpanded = true }
        )
    } else {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    }

    val dragModifier = if (item.absolutePath.isNotBlank()) {
        Modifier.deviceFileDragSource(
            absolutePath = item.absolutePath,
            sourceDeviceId = if (isRemoteTarget) sourceDeviceId else null,
            fileName = item.name,
            fileSize = item.sizeBytes
        )
    } else Modifier

    Box(modifier = Modifier.then(dragModifier)) {
        Surface(
            modifier = interactionModifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)),
            color = if (isFluxGlass) {
                if (isSelected) Color(0x4400E676) else Color(0x221E2D34)
            } else {
                if (isSelected) FileApexTeal.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            },
            border = if (isFluxGlass) BorderStroke(1.dp, if (isSelected) Color(0xFF00E676) else Color.White.copy(alpha = 0.15f)) else null,
            tonalElevation = 0.dp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val isItemLoading = isLoading && loadingFolderPath != null && pathsEqual(item.absolutePath, loadingFolderPath)
                    if (isItemLoading) {
                        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp,
                                color = if (isFluxGlass) Color(0xFF00E676) else MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        ExplorerEntryIcon(item = item, modifier = Modifier.size(40.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isFluxGlass) Color.White else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isFluxGlass) Color(0xFFCBD5E1) else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
                if (isSelectionMode && isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    ) {
                        SelectionIndicator(selected = true)
                    }
                }
            }
        }
        ItemContextMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            isRemoteTarget = isRemoteTarget,
            onCopy = onCopy,
            onSendToDevice = onSendToDevice,
            onDownload = onDownload
        )
    }
}

@Composable
private fun ItemContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    isRemoteTarget: Boolean,
    onCopy: () -> Unit,
    onSendToDevice: () -> Unit,
    onDownload: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            text = { Text(stringRes("copy_action")) },
            onClick = {
                onDismissRequest()
                onCopy()
            }
        )
        DropdownMenuItem(
            text = { Text(stringRes("send_to")) },
            onClick = {
                onDismissRequest()
                onSendToDevice()
            }
        )
        if (isRemoteTarget) {
            DropdownMenuItem(
                text = { Text(stringRes("download")) },
                onClick = {
                    onDismissRequest()
                    onDownload()
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun explorerItemRowModifier(
    desktopSelection: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onExtendSelect: () -> Unit,
    onActivate: () -> Unit,
    onSecondaryClick: () -> Unit
): Modifier = if (desktopSelection) {
    Modifier
        .fillMaxWidth()
        .desktopItemClicks(
            isSelectionMode = isSelectionMode,
            onClick = onClick,
            onToggleSelect = onToggleSelect,
            onExtendSelect = onExtendSelect,
            onActivate = onActivate,
            onSecondaryClick = onSecondaryClick
        )
} else {
    Modifier
        .fillMaxWidth()
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
}

private fun Modifier.desktopItemClicks(
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onExtendSelect: () -> Unit,
    onActivate: () -> Unit,
    onSecondaryClick: () -> Unit
): Modifier = pointerInput(
    isSelectionMode,
    onClick,
    onToggleSelect,
    onExtendSelect,
    onActivate,
    onSecondaryClick
) {
    awaitEachGesture {
        var downEvent = awaitPointerEvent(PointerEventPass.Main)
        while (downEvent.changes.none { it.changedToDown() }) {
            downEvent = awaitPointerEvent(PointerEventPass.Main)
        }
        val downChange = downEvent.changes.first { it.changedToDown() }
        val isSecondary = downEvent.buttons.isSecondaryPressed
        if (isSecondary) {
            downChange.consume()
            val up = waitForUpOrCancellation()
            if (up != null) {
                up.consume()
                onSecondaryClick()
            }
            return@awaitEachGesture
        }
        val toggleMulti = downEvent.keyboardModifiers.isMetaPressed ||
            downEvent.keyboardModifiers.isCtrlPressed
        val extendRange = downEvent.keyboardModifiers.isShiftPressed && !toggleMulti

        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
        up.consume()

        when {
            toggleMulti -> onToggleSelect()
            extendRange -> onExtendSelect()
            isSelectionMode -> onToggleSelect()
            else -> onClick()
        }

        val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
            awaitFirstDown(requireUnconsumed = false)
        }
        if (secondDown != null) {
            secondDown.consume()
            waitForUpOrCancellation()?.consume()
            onActivate()
        }
    }
}

@Composable
private fun SelectionIndicator(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(
                if (selected) FileApexTeal
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private fun pathsEqual(a: String, b: String): Boolean {
    fun norm(path: String) = path.replace('\\', '/').trimEnd('/')
    return norm(a) == norm(b)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${(kb * 10).toInt() / 10.0} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${(mb * 10).toInt() / 10.0} MB"
    val gb = mb / 1024.0
    return "${(gb * 10).toInt() / 10.0} GB"
}
