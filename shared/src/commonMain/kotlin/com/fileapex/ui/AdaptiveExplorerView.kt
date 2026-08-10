package com.fileapex.ui

import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.filled.Folder



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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
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
    paneDirectories: List<RemoteFileItem>,
    contentDirectories: List<RemoteFileItem>,
    contentFiles: List<RemoteFileItem>,
    selectedFolderPath: String?,
    canNavigateUp: Boolean,
    isSelectionMode: Boolean,
    selectedFileIds: Set<String>,
    onNavigateUp: () -> Unit,
    onPaneFolderClick: (RemoteFileItem) -> Unit,
    onContentDirectoryClick: (RemoteFileItem) -> Unit,
    onFileOpen: (RemoteFileItem) -> Unit,
    onFileLongPress: (RemoteFileItem) -> Unit,
    onFileSelectExclusive: (RemoteFileItem) -> Unit = {},
    onFileToggleSelect: (RemoteFileItem) -> Unit = {},
    onFileExtendSelect: (RemoteFileItem) -> Unit = {},
    onFileActivate: (RemoteFileItem) -> Unit = {},
    modifier: Modifier = Modifier,
    contentBottomPadding: Dp = 24.dp
) {
    val listPadding = PaddingValues(bottom = contentBottomPadding)
    val showingPaneRootFiles = selectedFolderPath == null
    val desktopSelection = usesDesktopFileSelection()

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
                        ParentRow(onClick = onNavigateUp)
                    }
                }
                items(paneDirectories, key = { "pane-${it.id}" }) { dir ->
                    val selected = selectedFolderPath != null &&
                        pathsEqual(dir.absolutePath, selectedFolderPath)
                    ExplorerListRow(
                        title = dir.name,
                        subtitle = "Folder",
                        selected = selected,
                        leading = {
                            ExplorerEntryIcon(item = dir, modifier = Modifier.size(28.dp))
                        },
                        onClick = { onPaneFolderClick(dir) }
                    )
                }
                if (paneDirectories.isEmpty() && !canNavigateUp) {
                    item(key = "pane-empty") {
                        EmptyHint("No folders")
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
                    "Select a folder on the left, or browse files below"
                } else {
                    "This folder is empty"
                },
                isSelectionMode = isSelectionMode,
                selectedFileIds = selectedFileIds,
                desktopSelection = desktopSelection,
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
                onFileActivate = onFileActivate
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
                text = "This folder is empty",
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
        emptyHint = "This folder is empty",
        isSelectionMode = isSelectionMode,
        selectedFileIds = selectedFileIds,
        desktopSelection = desktopSelection,
        listPadding = listPadding,
        modifier = modifier.fillMaxSize(),
        onNavigateUp = onNavigateUp,
        onDirectoryClick = onContentDirectoryClick,
        onFileOpen = onFileOpen,
        onFileLongPress = onFileLongPress,
        onFileSelectExclusive = onFileSelectExclusive,
        onFileToggleSelect = onFileToggleSelect,
        onFileExtendSelect = onFileExtendSelect,
        onFileActivate = onFileActivate
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
    listPadding: PaddingValues,
    modifier: Modifier,
    onNavigateUp: () -> Unit,
    onDirectoryClick: (RemoteFileItem) -> Unit,
    onFileOpen: (RemoteFileItem) -> Unit,
    onFileLongPress: (RemoteFileItem) -> Unit,
    onFileSelectExclusive: (RemoteFileItem) -> Unit,
    onFileToggleSelect: (RemoteFileItem) -> Unit,
    onFileExtendSelect: (RemoteFileItem) -> Unit,
    onFileActivate: (RemoteFileItem) -> Unit
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
            listPadding = listPadding,
            modifier = modifier,
            onNavigateUp = onNavigateUp,
            onDirectoryClick = onDirectoryClick,
            onFileOpen = onFileOpen,
            onFileLongPress = onFileLongPress,
            onFileSelectExclusive = onFileSelectExclusive,
            onFileToggleSelect = onFileToggleSelect,
            onFileExtendSelect = onFileExtendSelect,
            onFileActivate = onFileActivate
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
            listPadding = listPadding,
            modifier = modifier,
            onNavigateUp = onNavigateUp,
            onDirectoryClick = onDirectoryClick,
            onFileOpen = onFileOpen,
            onFileLongPress = onFileLongPress,
            onFileSelectExclusive = onFileSelectExclusive,
            onFileToggleSelect = onFileToggleSelect,
            onFileExtendSelect = onFileExtendSelect,
            onFileActivate = onFileActivate
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
    listPadding: PaddingValues,
    modifier: Modifier,
    onNavigateUp: () -> Unit,
    onDirectoryClick: (RemoteFileItem) -> Unit,
    onFileOpen: (RemoteFileItem) -> Unit,
    onFileLongPress: (RemoteFileItem) -> Unit,
    onFileSelectExclusive: (RemoteFileItem) -> Unit,
    onFileToggleSelect: (RemoteFileItem) -> Unit,
    onFileExtendSelect: (RemoteFileItem) -> Unit,
    onFileActivate: (RemoteFileItem) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = listPadding
    ) {
        if (canNavigateUp) {
            item(key = "parent") {
                ParentRow(onClick = onNavigateUp)
            }
        }
        if (isEmpty) {
            item(key = "content-empty") {
                EmptyHint(emptyHint)
            }
        }
        items(directories, key = { "dir-${it.id}" }) { dir ->
            ExplorerListRow(
                title = dir.name,
                subtitle = "Folder",
                selected = false,
                leading = {
                    ExplorerEntryIcon(item = dir, modifier = Modifier.size(28.dp))
                },
                onClick = { onDirectoryClick(dir) }
            )
        }
        items(files, key = { "file-${it.id}" }) { file ->
            FileListRow(
                file = file,
                isSelectionMode = isSelectionMode,
                isSelected = file.id in selectedFileIds,
                desktopSelection = desktopSelection,
                onClick = { onFileOpen(file) },
                onLongClick = { onFileLongPress(file) },
                onSelectExclusive = { onFileSelectExclusive(file) },
                onToggleSelect = { onFileToggleSelect(file) },
                onExtendSelect = { onFileExtendSelect(file) },
                onActivate = { onFileActivate(file) }
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
    listPadding: PaddingValues,
    modifier: Modifier,
    onNavigateUp: () -> Unit,
    onDirectoryClick: (RemoteFileItem) -> Unit,
    onFileOpen: (RemoteFileItem) -> Unit,
    onFileLongPress: (RemoteFileItem) -> Unit,
    onFileSelectExclusive: (RemoteFileItem) -> Unit,
    onFileToggleSelect: (RemoteFileItem) -> Unit,
    onFileExtendSelect: (RemoteFileItem) -> Unit,
    onFileActivate: (RemoteFileItem) -> Unit
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
                ParentRow(onClick = onNavigateUp)
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
                subtitle = "Folder",
                isSelectionMode = false,
                isSelected = false,
                desktopSelection = desktopSelection,
                onClick = { onDirectoryClick(dir) },
                onLongClick = {},
                onSelectExclusive = {},
                onToggleSelect = {},
                onExtendSelect = {},
                onActivate = { onDirectoryClick(dir) }
            )
        }
        items(files, key = { "gfile-${it.id}" }) { file ->
            ExplorerGridCell(
                item = file,
                subtitle = formatBytes(file.sizeBytes),
                isSelectionMode = isSelectionMode,
                isSelected = file.id in selectedFileIds,
                desktopSelection = desktopSelection,
                onClick = { onFileOpen(file) },
                onLongClick = { onFileLongPress(file) },
                onSelectExclusive = { onFileSelectExclusive(file) },
                onToggleSelect = { onFileToggleSelect(file) },
                onExtendSelect = { onFileExtendSelect(file) },
                onActivate = { onFileActivate(file) }
            )
        }
    }
}

@Composable
private fun ParentRow(onClick: () -> Unit) {
    val isFluxGlass = LocalAppTheme.current == AppTheme.FLUX_GLASS
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = "Up",
            tint = if (isFluxGlass) Color(0xFF00E676) else FileApexTeal,
            modifier = Modifier.size(28.dp)
        )
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
                text = "Up one folder",
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

@Composable
private fun ExplorerListRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    leading: @Composable () -> Unit,
    onClick: () -> Unit
) {
    val isFluxGlass = LocalAppTheme.current == AppTheme.FLUX_GLASS
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) {
                    if (isFluxGlass) Color(0x4400E676) else FileApexTeal.copy(alpha = 0.14f)
                } else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (isFluxGlass) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isFluxGlass) Color(0xFFCBD5E1) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectExclusive: () -> Unit,
    onToggleSelect: () -> Unit,
    onExtendSelect: () -> Unit,
    onActivate: () -> Unit
) {
    val rowModifier = fileRowModifier(
        desktopSelection = desktopSelection,
        onClick = onClick,
        onLongClick = onLongClick,
        onSelectExclusive = onSelectExclusive,
        onToggleSelect = onToggleSelect,
        onExtendSelect = onExtendSelect,
        onActivate = onActivate
    )
    val isFluxGlass = LocalAppTheme.current == AppTheme.FLUX_GLASS

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
                style = MaterialTheme.typography.bodyLarge,
                color = if (isFluxGlass) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatBytes(file.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = if (isFluxGlass) Color(0xFFCBD5E1) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider(color = if (isFluxGlass) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExplorerGridCell(
    item: RemoteFileItem,
    subtitle: String,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    desktopSelection: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectExclusive: () -> Unit,
    onToggleSelect: () -> Unit,
    onExtendSelect: () -> Unit,
    onActivate: () -> Unit
) {
    val isFluxGlass = LocalAppTheme.current == AppTheme.FLUX_GLASS
    val interactionModifier = when {
        item.isDirectory -> Modifier.clickable(onClick = onClick)
        desktopSelection -> Modifier.desktopFileSelectionClicks(
            onSelectExclusive = onSelectExclusive,
            onToggleSelect = onToggleSelect,
            onExtendSelect = onExtendSelect,
            onActivate = onActivate
        )
        else -> Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    }
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
                ExplorerEntryIcon(item = item, modifier = Modifier.size(40.dp))
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
}

@OptIn(ExperimentalFoundationApi::class)
private fun fileRowModifier(
    desktopSelection: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectExclusive: () -> Unit,
    onToggleSelect: () -> Unit,
    onExtendSelect: () -> Unit,
    onActivate: () -> Unit
): Modifier = if (desktopSelection) {
    Modifier
        .fillMaxWidth()
        .desktopFileSelectionClicks(
            onSelectExclusive = onSelectExclusive,
            onToggleSelect = onToggleSelect,
            onExtendSelect = onExtendSelect,
            onActivate = onActivate
        )
} else {
    Modifier
        .fillMaxWidth()
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
}

private fun Modifier.desktopFileSelectionClicks(
    onSelectExclusive: () -> Unit,
    onToggleSelect: () -> Unit,
    onExtendSelect: () -> Unit,
    onActivate: () -> Unit
): Modifier = pointerInput(
    onSelectExclusive,
    onToggleSelect,
    onExtendSelect,
    onActivate
) {
    awaitEachGesture {
        var downEvent = awaitPointerEvent(PointerEventPass.Main)
        while (downEvent.changes.none { it.changedToDown() }) {
            downEvent = awaitPointerEvent(PointerEventPass.Main)
        }
        val downChange = downEvent.changes.first { it.changedToDown() }
        val toggleMulti = downEvent.keyboardModifiers.isMetaPressed ||
            downEvent.keyboardModifiers.isCtrlPressed
        val extendRange = downEvent.keyboardModifiers.isShiftPressed && !toggleMulti
        downChange.consume()

        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
        up.consume()

        when {
            toggleMulti -> onToggleSelect()
            extendRange -> onExtendSelect()
            else -> onSelectExclusive()
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
