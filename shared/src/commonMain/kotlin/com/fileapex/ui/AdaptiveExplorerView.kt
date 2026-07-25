package com.fileapex.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.ui.theme.FileApexTeal
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phone: single list of folders + files with ".." at top.
 * Wide/fold: left = folders at the current parent (siblings), right = contents of the selected folder.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AdaptiveExplorerView(
    isWideDisplay: Boolean,
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
                    ExplorerRow(
                        title = dir.name,
                        subtitle = "Folder",
                        selected = selected,
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
            LazyColumn(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight(),
                contentPadding = listPadding
            ) {
                if (canNavigateUp) {
                    item(key = "content-parent") {
                        ParentRow(onClick = onNavigateUp)
                    }
                }
                if (rightEmpty) {
                    item(key = "content-empty") {
                        EmptyHint(
                            if (showingPaneRootFiles) {
                                "Select a folder on the left, or browse files below"
                            } else {
                                "This folder is empty"
                            }
                        )
                    }
                }
                items(rightDirs, key = { "cdir-${it.id}" }) { dir ->
                    ExplorerRow(
                        title = dir.name,
                        subtitle = "Folder",
                        selected = false,
                        onClick = { onContentDirectoryClick(dir) }
                    )
                }
                items(rightFiles, key = { "cfile-${it.id}" }) { file ->
                    FileRow(
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
        return
    }

    // Phone / narrow: unified list.
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = listPadding
    ) {
        if (canNavigateUp) {
            item(key = "parent") {
                ParentRow(onClick = onNavigateUp)
            }
        }
        items(contentDirectories, key = { it.id }) { dir ->
            ExplorerRow(
                title = dir.name,
                subtitle = "Folder",
                selected = false,
                onClick = { onContentDirectoryClick(dir) }
            )
        }
        items(contentFiles, key = { "f-${it.id}" }) { file ->
            FileRow(
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

@Composable
private fun ParentRow(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = "..",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1
        )
        Text(
            text = "Up one folder",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider()
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ExplorerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) FileApexTeal.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0f)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
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
    val rowModifier = if (desktopSelection) {
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
    Row(
        modifier = rowModifier
            .background(
                if (isSelected) FileApexTeal.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            SelectionIndicator(selected = isSelected)
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyLarge,
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
    HorizontalDivider()
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
        // Wait for primary press (ignore hover/move).
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

        // Select immediately (Finder-like); double-click also opens.
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
