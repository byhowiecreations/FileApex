package com.fileapex.ui.explorer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.i18n.stringRes
import com.fileapex.platform.decodeLocalImageFile
import com.fileapex.platform.decodeVideoPoster
import com.fileapex.ui.ExplorerEntryIcon
import com.fileapex.ui.ExplorerIcon
import com.fileapex.ui.JadedFileGlyph
import com.fileapex.ui.JadedFolderGlyph
import com.fileapex.ui.dnd.deviceFileDragSource
import com.fileapex.ui.dnd.reportDropSpot
import com.fileapex.ui.dropDestinationFrame
import com.fileapex.ui.dropTargetHot
import com.fileapex.ui.dropTargetTint
import com.fileapex.ui.explorerIcon
import com.fileapex.ui.explorerInk
import com.fileapex.ui.explorerItemRowModifier
import com.fileapex.ui.formatBytes
import com.fileapex.ui.pathsEqual
import com.fileapex.ui.theme.FileApexTeal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PaneDirectoryRow(
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
    val ink = explorerInk()
    val rowModifier = explorerItemRowModifier(
        desktopSelection = desktopSelection,
        isSelectionMode = isSelectionMode,
        onClick = onClick,
        onLongClick = { menuExpanded = true },
        onToggleSelect = onToggleSelect,
        onExtendSelect = onExtendSelect,
        onActivate = onActivate,
        onSecondaryClick = { menuExpanded = true }
    )

    val folderHot = dropTargetHot(dir.absolutePath)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .reportDropSpot(key = "pane:${dir.absolutePath}", destinationPath = dir.absolutePath)
            .dropDestinationFrame(folderHot)
    ) {
        val selectedInPane = isChecked || isSelectedInPane
        Row(
            modifier = rowModifier
                .background(
                    when {
                        folderHot -> dropTargetTint()
                        selectedInPane -> ink.paneSelected
                        else -> Color.Transparent
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
                        color = ink.accent
                    )
                }
            } else if (ink.jadedGlyphs) {
                JadedFolderGlyph(name = dir.name, modifier = Modifier.size(28.dp))
            } else {
                ExplorerEntryIcon(
                    item = dir,
                    modifier = Modifier.size(28.dp),
                    folderColor = if (ink.styledKinetic) ink.folderTint else null
                )
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
                    color = ink.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = com.fileapex.i18n.AppI18n.t("folder"),
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.muted
                )
            }
        }
        val bar = ink.selectionBar
        if (bar != null && selectedInPane) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .wrapContentWidth(Alignment.Start)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(bar)
            )
        }
        FavoriteMark(dir.absolutePath, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
        ItemContextMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            isRemoteTarget = isRemoteTarget,
            onCopy = onCopy,
            onSendToDevice = onSendToDevice,
            onDownload = onDownload,
            item = dir,
            onOpen = onActivate,
            onSelect = onLongClick
        )
    }
    HorizontalDivider(color = if (ink.jadedGlyphs) Color.White.copy(alpha = 0.08f) else ink.divider)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DirectoryListRow(
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
    val ink = explorerInk()
    val rowModifier = explorerItemRowModifier(
        desktopSelection = desktopSelection,
        isSelectionMode = isSelectionMode,
        onClick = onClick,
        onLongClick = { menuExpanded = true },
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

    val folderHot = dropTargetHot(dir.absolutePath)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .reportDropSpot(key = "dir:${dir.absolutePath}", destinationPath = dir.absolutePath)
            .then(dragModifier)
            .dropDestinationFrame(folderHot)
    ) {
        Row(
            modifier = rowModifier
                .background(
                    when {
                        folderHot -> dropTargetTint()
                        isSelected -> ink.listSelected
                        else -> ink.listIdle
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
                        color = ink.accent
                    )
                }
            } else if (ink.jadedGlyphs) {
                JadedFolderGlyph(name = dir.name, modifier = Modifier.size(28.dp))
            } else {
                ExplorerEntryIcon(
                    item = dir,
                    modifier = Modifier.size(28.dp),
                    folderColor = if (ink.styledKinetic) ink.folderTint else null
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = dir.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = ink.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = com.fileapex.i18n.AppI18n.t("folder"),
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.muted
                )
            }
        }
        FavoriteMark(dir.absolutePath, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
        ItemContextMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            isRemoteTarget = isRemoteTarget,
            onCopy = onCopy,
            onSendToDevice = onSendToDevice,
            onDownload = onDownload,
            item = dir,
            onOpen = onActivate,
            onSelect = onLongClick
        )
    }
    HorizontalDivider(color = ink.divider)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileListRow(
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
    val ink = explorerInk()
    val rowModifier = explorerItemRowModifier(
        desktopSelection = desktopSelection,
        isSelectionMode = isSelectionMode,
        onClick = onClick,
        onLongClick = { menuExpanded = true },
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(dragModifier)
    ) {
        Row(
            modifier = rowModifier
                .background(if (isSelected) ink.listSelected else ink.listIdle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                SelectionIndicator(selected = isSelected)
                Spacer(modifier = Modifier.width(12.dp))
            }
            ExplorerFileVisual(
                item = file,
                jaded = ink.jadedGlyphs,
                isRemoteTarget = isRemoteTarget,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = ink.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatBytes(file.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.muted
                )
            }
        }
        FavoriteMark(file.absolutePath, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
        ItemContextMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            isRemoteTarget = isRemoteTarget,
            onCopy = onCopy,
            onSendToDevice = onSendToDevice,
            onDownload = onDownload,
            item = file,
            onOpen = onActivate,
            onSelect = onLongClick
        )
    }
    HorizontalDivider(color = ink.divider)
}

@Composable
internal fun SelectionIndicator(selected: Boolean) {
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

@Composable
internal fun ExplorerFileVisual(
    item: RemoteFileItem,
    jaded: Boolean,
    isRemoteTarget: Boolean,
    modifier: Modifier
) {
    val thumb = rememberMediaThumb(item, isRemoteTarget)
    if (thumb != null) {
        Image(
            bitmap = thumb,
            contentDescription = item.name,
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
    } else if (jaded) {
        JadedFileGlyph(item = item, modifier = modifier)
    } else {
        ExplorerEntryIcon(item = item, modifier = modifier)
    }
}

@Composable
private fun rememberMediaThumb(item: RemoteFileItem, isRemoteTarget: Boolean): ImageBitmap? {
    if (isRemoteTarget || item.isDirectory) return null
    val image = item.mimeType.startsWith("image/")
    val video = item.mimeType.startsWith("video/")
    if (!image && !video) return null
    if (item.absolutePath.isBlank()) return null
    var bitmap by remember(item.absolutePath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.absolutePath) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                if (video) {
                    decodeVideoPoster(item.absolutePath, 256)
                } else {
                    decodeLocalImageFile(item.absolutePath, 256)
                }
            }.getOrNull()
        }
    }
    return bitmap
}

@Composable
internal fun FavoriteMark(path: String, modifier: Modifier = Modifier) {
    val favorites by FileApexServices.settings.explorerFavorites.collectAsState()
    if (path in favorites) {
        Icon(
            explorerIcon(ExplorerIcon.FavoriteOn),
            contentDescription = stringRes("favorite"),
            tint = Color(0xFFFFC857),
            modifier = modifier.size(18.dp)
        )
    }
}
