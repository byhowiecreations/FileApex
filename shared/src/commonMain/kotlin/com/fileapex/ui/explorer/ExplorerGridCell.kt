package com.fileapex.ui.explorer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.ui.JadedCardCornerBadge
import com.fileapex.ui.JadedFolderGlyph
import com.fileapex.ui.androidPressThenRelease
import com.fileapex.ui.desktopItemClicks
import com.fileapex.ui.dnd.deviceFileDragSource
import com.fileapex.ui.dnd.reportDropSpot
import com.fileapex.ui.dropDestinationFrame
import com.fileapex.ui.dropTargetHot
import com.fileapex.ui.explorerInk
import com.fileapex.ui.pathsEqual
import com.fileapex.ui.theme.LocalJadedHazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExplorerGridCell(
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
    val ink = explorerInk()
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
        Modifier.androidPressThenRelease(
            onClick = onClick,
            onLongPressRelease = { menuExpanded = true }
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

    val jadedShape = RoundedCornerShape(16.dp)
    val cardChrome = if (ink.jadedGlyphs) {
        val haze = LocalJadedHazeState.current
        Modifier
            .aspectRatio(1f)
            .clip(jadedShape)
            .then(
                if (haze != null) {
                    Modifier.hazeEffect(
                        state = haze,
                        style = HazeStyle(
                            backgroundColor = Color.Transparent,
                            tints = listOf(HazeTint(Color.White.copy(alpha = 0.10f))),
                            blurRadius = 24.dp,
                            noiseFactor = 0.04f
                        )
                    )
                } else {
                    Modifier
                }
            )
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.16f),
                    1f to Color.White.copy(alpha = 0.05f)
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.22f),
                    1f to Color.White.copy(alpha = 0.10f)
                ),
                shape = jadedShape
            )
    } else {
        Modifier
    }
    val cellHot = item.isDirectory && dropTargetHot(item.absolutePath)
    Box(
        modifier = Modifier
            .then(
                if (item.isDirectory) {
                    Modifier.reportDropSpot(key = "grid:${item.absolutePath}", destinationPath = item.absolutePath)
                } else {
                    Modifier
                }
            )
            .then(dragModifier)
            .dropDestinationFrame(cellHot)
    ) {
        if (ink.jadedGlyphs) {
            Box(modifier = interactionModifier.then(cardChrome)) {
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
                                color = ink.accent
                            )
                        }
                    } else if (item.isDirectory) {
                        JadedFolderGlyph(name = item.name, modifier = Modifier.size(36.dp))
                    } else {
                        ExplorerFileVisual(
                            item = item,
                            jaded = true,
                            isRemoteTarget = isRemoteTarget,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = ink.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = ink.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
                if (!item.isDirectory) {
                    JadedCardCornerBadge(
                        item = item,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
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
        } else {
        Surface(
            modifier = interactionModifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(ink.gridCorner)),
            color = ink.gridFill(isSelected),
            border = ink.gridBorder?.invoke(isSelected),
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
                                color = ink.accent
                            )
                        }
                    } else if (ink.jadedGlyphs && item.isDirectory) {
                        JadedFolderGlyph(name = item.name, modifier = Modifier.size(36.dp))
                    } else {
                        ExplorerFileVisual(
                            item = item,
                            jaded = ink.jadedGlyphs,
                            isRemoteTarget = isRemoteTarget,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = ink.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = ink.muted,
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
        FavoriteMark(item.absolutePath, Modifier.align(Alignment.TopStart).padding(6.dp))
        ItemContextMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            isRemoteTarget = isRemoteTarget,
            onCopy = onCopy,
            onSendToDevice = onSendToDevice,
            onDownload = onDownload,
            item = item,
            onOpen = onActivate,
            onSelect = onLongClick
        )
    }
}
