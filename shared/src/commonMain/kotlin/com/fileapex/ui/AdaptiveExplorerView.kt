package com.fileapex.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.di.FileApexServices
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.i18n.stringRes
import com.fileapex.platform.horizontalResizePointerIcon
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.dnd.LocalDropParentPath
import com.fileapex.ui.dnd.LocalExplorerDropHighlight
import com.fileapex.ui.dnd.reportDropSpot
import com.fileapex.ui.explorer.EmptyHint
import com.fileapex.ui.explorer.ExplorerContentPane
import com.fileapex.ui.explorer.PaneDirectoryRow
import com.fileapex.ui.explorer.ParentRow
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.ui.theme.LocalJadedHazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
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
    contentBottomPadding: Dp = 24.dp,
    contentDropPath: String = ""
) {
    val listPadding = PaddingValues(bottom = contentBottomPadding)
    val contentParent = contentDropPath.ifBlank { selectedFolderPath ?: panePath }
    val showingPaneRootFiles = selectedFolderPath == null
    val desktopSelection = usesDesktopFileSelection()

    LaunchedEffect(isWideDisplay, panePath, selectedFolderPath) {
        if (isWideDisplay && selectedFolderPath == null && paneDirectories.isNotEmpty()) {
            onPreviewFirstSplitPaneFolder()
        }
    }

    if (isWideDisplay) {
        val rightDirs = contentDirectories
        val rightFiles = contentFiles
        val rightEmpty = rightDirs.isEmpty() && rightFiles.isEmpty()

        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val totalWidthPx = constraints.maxWidth.toFloat()
            val splitFraction by FileApexServices.settings.explorerSplitFraction.collectAsState()
            var isDraggingDivider by remember { mutableStateOf(false) }
            val dividerInteraction = remember { MutableInteractionSource() }
            val isHovered by dividerInteraction.collectIsHoveredAsState()

            val ink = explorerInk()
            Row(modifier = Modifier.fillMaxSize()) {
                val leftPaneModifier = if (ink.jadedGlyphs) {
                    val haze = LocalJadedHazeState.current
                    val paneShape = RoundedCornerShape(16.dp)
                    Modifier
                        .weight(splitFraction)
                        .fillMaxHeight()
                        .padding(start = 12.dp, top = 8.dp, end = 6.dp, bottom = contentBottomPadding)
                        .clip(paneShape)
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
                            shape = paneShape
                        )
                } else if (LocalAppTheme.current.let { it == AppTheme.SIMPLE || it == AppTheme.CAROUSEL }) {
                    // Same inset, rounded pane as Jaded Steel; the colors stay Simple's own.
                    val paneShape = RoundedCornerShape(20.dp)
                    Modifier
                        .weight(splitFraction)
                        .fillMaxHeight()
                        .padding(start = 12.dp, top = 8.dp, end = 6.dp, bottom = contentBottomPadding)
                        .clip(paneShape)
                        .background(ink.paneBackground)
                        .border(1.dp, ink.divider, paneShape)
                } else {
                    Modifier
                        .weight(splitFraction)
                        .fillMaxHeight()
                        .background(ink.paneBackground)
                }
                CompositionLocalProvider(LocalDropParentPath provides panePath) {
                Column(modifier = leftPaneModifier) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().listingDropChrome(),
                    contentPadding = if (ink.jadedGlyphs) PaddingValues(0.dp) else listPadding
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
                }
                }
                Box(
                    modifier = Modifier
                        .width(if (ink.jadedGlyphs) 14.dp else 10.dp)
                        .fillMaxHeight()
                        .hoverable(dividerInteraction)
                        .pointerHoverIcon(horizontalResizePointerIcon())
                        .pointerInput(totalWidthPx) {
                            detectHorizontalDragGestures(
                                onDragStart = { isDraggingDivider = true },
                                onDragEnd = {
                                    isDraggingDivider = false
                                    FileApexServices.settings.setExplorerSplitFraction(FileApexServices.settings.explorerSplitFraction.value)
                                },
                                onDragCancel = {
                                    isDraggingDivider = false
                                    FileApexServices.settings.setExplorerSplitFraction(FileApexServices.settings.explorerSplitFraction.value)
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                if (totalWidthPx > 0f) {
                                    val current = FileApexServices.settings.explorerSplitFraction.value
                                    val deltaFraction = dragAmount / totalWidthPx
                                    FileApexServices.settings.setExplorerSplitFraction(current + deltaFraction, persist = false)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    VerticalDivider(
                        thickness = if (isHovered || isDraggingDivider) 2.dp else 1.dp,
                        color = when {
                            ink.jadedGlyphs && !isHovered && !isDraggingDivider -> Color.Transparent
                            isHovered || isDraggingDivider ->
                                if (ink.styledKinetic) ink.accent else FileApexTeal
                            ink.styledKinetic -> ink.divider
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                    )
                }
                CompositionLocalProvider(LocalDropParentPath provides contentParent) {
                ExplorerContentPane(
                    viewMode = viewMode,
                    canNavigateUp = false,
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
                        .weight(1f - splitFraction)
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
            }
        }
        return
    }

    CompositionLocalProvider(LocalDropParentPath provides contentParent) {
    val empty = contentDirectories.isEmpty() && contentFiles.isEmpty()
    if (empty && !canNavigateUp) {
        val ink = explorerInk()
        Box(
            modifier = modifier.fillMaxSize().listingDropChrome(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringRes("folder_empty"),
                style = MaterialTheme.typography.bodyLarge,
                color = ink.muted
            )
        }
        return@CompositionLocalProvider
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
}

@OptIn(ExperimentalFoundationApi::class)
internal fun explorerItemRowModifier(
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
        .androidPressThenRelease(onClick = onClick, onLongPressRelease = onLongClick)
}

private val holdWithoutOpening: () -> Unit = {}

internal fun Modifier.androidPressThenRelease(
    onClick: () -> Unit,
    onLongPressRelease: () -> Unit
): Modifier = this

    .combinedClickable(onClick = onClick, onLongClick = holdWithoutOpening)

    .pointerInput(onClick, onLongPressRelease) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            val slop = viewConfiguration.touchSlop
            val deadline = System.nanoTime() + viewConfiguration.longPressTimeoutMillis * 1_000_000L
            var held = true
            while (System.nanoTime() < deadline) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) {
                    held = false
                    break
                }
                if ((change.position - start).getDistance() > slop) return@awaitEachGesture
            }
            if (!held) return@awaitEachGesture
            var dragged = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if ((change.position - start).getDistance() > slop) dragged = true
                if (!change.pressed) {
                    if (!dragged) {
                        change.consume()
                        onLongPressRelease()
                    }
                    return@awaitEachGesture
                }
            }
        }
    }

internal fun Modifier.desktopItemClicks(
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

internal fun pathsEqual(a: String, b: String): Boolean {
    fun norm(path: String) = path.replace('\\', '/').trimEnd('/')
    return norm(a) == norm(b)
}

internal fun formatBytes(bytes: Long): String = com.fileapex.platform.formatHostFileSize(bytes)

@Composable
internal fun dropTargetTint(): Color {
    val ink = explorerInk()
    val color = if (ink.jadedGlyphs || ink.styledKinetic) ink.accent else MaterialTheme.colorScheme.primary
    return color.copy(alpha = 0.22f)
}

@Composable
internal fun dropTargetHot(path: String): Boolean {
    if (path.isBlank()) return false
    return LocalExplorerDropHighlight.current.path == path
}

@Composable
internal fun Modifier.dropDestinationFrame(active: Boolean): Modifier {
    if (!active) return this
    val ink = explorerInk()
    val color = if (ink.jadedGlyphs || ink.styledKinetic) ink.accent else MaterialTheme.colorScheme.primary
    return this.border(2.dp, color, RoundedCornerShape(10.dp))
}

@Composable
internal fun Modifier.listingDropChrome(): Modifier {
    val parent = LocalDropParentPath.current
    val hot = dropTargetHot(parent)
    val framed = this
        .reportDropSpot(key = "list:$parent", destinationPath = parent)
        .dropDestinationFrame(hot)
    return if (hot) framed.background(dropTargetTint()) else framed
}
