package com.fileapex.ui.explorer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.JadedFolderGlyph
import com.fileapex.ui.dnd.LocalDropParentPath
import com.fileapex.ui.dnd.dropParentOf
import com.fileapex.ui.dnd.reportDropSpot
import com.fileapex.ui.dropDestinationFrame
import com.fileapex.ui.dropTargetHot
import com.fileapex.ui.dropTargetTint
import com.fileapex.ui.explorerInk
import com.fileapex.ui.formatBytes
import com.fileapex.ui.listingDropChrome
import com.fileapex.ui.pathsEqual

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExplorerContentPane(
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
        ExplorerViewMode.Split -> ExplorerListContent(
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
        modifier = modifier.listingDropChrome(),
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
    val jadedGrid = explorerInk().jadedGlyphs
    val gridGap = if (jadedGrid) 12.dp else 8.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = modifier.listingDropChrome(),
        contentPadding = if (jadedGrid) {
            PaddingValues(
                start = 12.dp,
                top = 12.dp,
                end = 12.dp,
                bottom = maxOf(listPadding.calculateBottomPadding(), 12.dp)
            )
        } else {
            listPadding
        },
        horizontalArrangement = Arrangement.spacedBy(gridGap),
        verticalArrangement = Arrangement.spacedBy(gridGap)
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
internal fun ParentRow(onClick: () -> Unit, isLoading: Boolean = false) {
    val ink = explorerInk()
    val parentDest = dropParentOf(LocalDropParentPath.current).orEmpty()
    val hot = dropTargetHot(parentDest)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .reportDropSpot(key = "up:$parentDest", destinationPath = parentDest)
            .dropDestinationFrame(hot)
            .background(if (hot) dropTargetTint() else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = ink.accent
                )
            }
        } else if (ink.jadedGlyphs) {
            JadedFolderGlyph(name = "..", modifier = Modifier.size(28.dp))
        } else {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = stringRes("up"),
                tint = ink.folderTint,
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
                color = ink.title,
                maxLines = 1
            )
            Text(
                text = stringRes("up_one_folder"),
                style = MaterialTheme.typography.bodySmall,
                color = ink.muted
            )
        }
    }
    HorizontalDivider(color = if (ink.jadedGlyphs) Color.White.copy(alpha = 0.08f) else ink.divider)
}

@Composable
internal fun EmptyHint(text: String) {
    val ink = explorerInk()
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = ink.muted
    )
}
