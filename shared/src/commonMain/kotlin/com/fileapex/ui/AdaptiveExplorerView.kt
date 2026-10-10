package com.fileapex.ui

import com.fileapex.i18n.stringRes
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import com.fileapex.ui.theme.LocalJadedHazeState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.filled.Folder
import com.fileapex.ui.dnd.LocalDropParentPath
import com.fileapex.ui.dnd.LocalExplorerDropHighlight
import com.fileapex.ui.dnd.deviceFileDragSource
import com.fileapex.ui.dnd.dropParentOf
import com.fileapex.ui.dnd.reportDropSpot
import androidx.compose.runtime.CompositionLocalProvider



import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.platform.horizontalResizePointerIcon
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.ui.theme.KineticStyleLook
import com.fileapex.data.settings.traits
import com.fileapex.data.settings.ThemeShapeStyle
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.theme.FileApexTeal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.graphics.ImageBitmap
import com.fileapex.domain.preview.FilePreviewManager
import com.fileapex.platform.decodeLocalImageFile
import com.fileapex.platform.decodeVideoPoster

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
                } else if (LocalAppTheme.current == AppTheme.SIMPLE) {
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
private fun ParentRow(onClick: () -> Unit, isLoading: Boolean = false) {
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
private fun EmptyHint(text: String) {
    val ink = explorerInk()
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = ink.muted
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

@Composable
internal fun ItemContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    isRemoteTarget: Boolean,
    onCopy: () -> Unit,
    onSendToDevice: () -> Unit,
    onDownload: () -> Unit,
    item: RemoteFileItem? = null,
    onOpen: () -> Unit = {},
    onSelect: () -> Unit = {},
) {
    val mutations = LocalExplorerMutations.current
    var infoOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    var remoteDeleteOpen by remember { mutableStateOf(false) }
    var renameText by remember(item?.id) { mutableStateOf(TextFieldValue(item?.name.orEmpty())) }
    val traits = LocalAppTheme.current.traits
    val menuShape = when (traits.shapeStyle) {
        ThemeShapeStyle.Pill -> RoundedCornerShape(percent = 50)
        ThemeShapeStyle.RoundedSquare -> RoundedCornerShape(12.dp)
    }
    val family = explorerIconFamily()
    val iconTint = if (traits.orbitalHome && LocalKineticStyle.current == KineticStyle.JADED_STEEL) {
        KineticStyleLook.steel
    } else MaterialTheme.colorScheme.primary
    @Composable
    fun MenuGlyph(icon: ExplorerIcon, tint: Color = iconTint) {
        Icon(explorerIcon(icon, family), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
    val favorites by FileApexServices.settings.explorerFavorites.collectAsState()
    val local = item != null && !isRemoteTarget && mutations != null
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = menuShape
    ) {
        if (item != null) {
            DropdownMenuItem(
                text = { Text(stringRes("open")) },
                leadingIcon = { MenuGlyph(ExplorerIcon.Open) },
                onClick = {
                    onDismissRequest()
                    onOpen()
                }
            )
        }
        DropdownMenuItem(
            text = { Text(stringRes("copy_action")) },
            leadingIcon = { MenuGlyph(ExplorerIcon.Copy) },
            onClick = {
                onDismissRequest()
                onCopy()
            }
        )
        if (local) {
            DropdownMenuItem(
                text = { Text(stringRes("move_action")) },
                leadingIcon = { MenuGlyph(ExplorerIcon.Move) },
                onClick = {
                    onDismissRequest()
                    moveOpen = true
                }
            )
        }
        DropdownMenuItem(
            text = { Text(stringRes("send_to")) },
            leadingIcon = { MenuGlyph(ExplorerIcon.SendTo) },
            onClick = {
                onDismissRequest()
                onSendToDevice()
            }
        )
        if (isRemoteTarget) {
            DropdownMenuItem(
                text = { Text(stringRes("download")) },
                leadingIcon = { MenuGlyph(ExplorerIcon.Download) },
                onClick = {
                    onDismissRequest()
                    onDownload()
                }
            )
        }
        if (item != null && !isRemoteTarget) {
            val starred = item.absolutePath in favorites
            DropdownMenuItem(
                text = { Text(stringRes(if (starred) "unfavorite" else "favorite")) },
                leadingIcon = { MenuGlyph(if (starred) ExplorerIcon.FavoriteOn else ExplorerIcon.Favorite) },
                onClick = {
                    onDismissRequest()
                    FileApexServices.settings.toggleExplorerFavorite(item.absolutePath)
                }
            )
        }
        if (local) {
            DropdownMenuItem(
                text = { Text(stringRes("rename")) },
                leadingIcon = { MenuGlyph(ExplorerIcon.Rename) },
                onClick = {
                    onDismissRequest()
                    // Pre-select the name without its extension so typing replaces just the stem.
                    val stem = item.name.substringBeforeLast('.', item.name).length
                    renameText = TextFieldValue(item.name, TextRange(0, stem))
                    renameOpen = true
                }
            )
            val zip = item.name.endsWith(".zip", ignoreCase = true)
            DropdownMenuItem(
                text = { Text(stringRes(if (zip) "uncompress" else "compress")) },
                leadingIcon = { MenuGlyph(if (zip) ExplorerIcon.Uncompress else ExplorerIcon.Compress) },
                onClick = {
                    onDismissRequest()
                    if (zip) mutations.uncompress(item) else mutations.compress(item)
                }
            )
        }
        if (item != null) {
            DropdownMenuItem(
                text = { Text(stringRes("get_info")) },
                leadingIcon = { MenuGlyph(ExplorerIcon.Info) },
                onClick = {
                    onDismissRequest()
                    infoOpen = true
                }
            )
        }
        if ((local || isRemoteTarget) && item != null && mutations != null) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringRes("delete"), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { MenuGlyph(ExplorerIcon.Delete, MaterialTheme.colorScheme.error) },
                onClick = {
                    onDismissRequest()
                    // Local deletes go to the trash and can be undone; a remote one is confirmed first.
                    if (isRemoteTarget) remoteDeleteOpen = true else mutations.delete(item)
                }
            )
        }
    }
    if (remoteDeleteOpen && item != null && mutations != null) {
        AlertDialog(
            onDismissRequest = { remoteDeleteOpen = false },
            title = { Text(stringRes("remote_delete_confirm_title")) },
            text = { Text(stringRes("remote_delete_confirm_body", item.name)) },
            confirmButton = {
                TextButton(onClick = {
                    remoteDeleteOpen = false
                    mutations.delete(item)
                }) { Text(stringRes("delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { remoteDeleteOpen = false }) { Text(stringRes("cancel")) } }
        )
    }
    if (moveOpen && item != null && mutations != null) {
        FolderPickerDialog(
            startPath = item.absolutePath.substringBeforeLast('/'),
            title = stringRes("move_to_title", item.name),
            confirmLabel = stringRes("move_here"),
            onPick = { destination ->
                moveOpen = false
                mutations.move(item, destination)
            },
            onDismiss = { moveOpen = false }
        )
    }
    if (infoOpen && item != null) {
        AlertDialog(
            onDismissRequest = { infoOpen = false },
            title = { Text(item.name) },
            text = {
                Text(
                    listOf(item.absolutePath, formatBytes(item.sizeBytes), item.mimeType)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                )
            },
            confirmButton = {
                TextButton(onClick = { infoOpen = false }) { Text(stringRes("close")) }
            }
        )
    }
    if (renameOpen && item != null && mutations != null) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text(stringRes("rename")) },
            text = {
                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                // Wraps over several lines so a long name is fully visible and every part can be tapped.
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = false,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameOpen = false
                    mutations.rename(item, renameText.text)
                }) { Text(stringRes("rename")) }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text(stringRes("cancel")) }
            }
        )
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
        .androidPressThenRelease(onClick = onClick, onLongPressRelease = onLongClick)
}

private val holdWithoutOpening: () -> Unit = {}

private fun Modifier.androidPressThenRelease(
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

@Composable
private fun ExplorerFileVisual(
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

private fun formatBytes(bytes: Long): String = com.fileapex.platform.formatHostFileSize(bytes)

@Composable
private fun dropTargetTint(): Color {
    val ink = explorerInk()
    val color = if (ink.jadedGlyphs || ink.styledKinetic) ink.accent else MaterialTheme.colorScheme.primary
    return color.copy(alpha = 0.22f)
}

@Composable
private fun dropTargetHot(path: String): Boolean {
    if (path.isBlank()) return false
    return LocalExplorerDropHighlight.current.path == path
}

@Composable
private fun Modifier.dropDestinationFrame(active: Boolean): Modifier {
    if (!active) return this
    val ink = explorerInk()
    val color = if (ink.jadedGlyphs || ink.styledKinetic) ink.accent else MaterialTheme.colorScheme.primary
    return this.border(2.dp, color, RoundedCornerShape(10.dp))
}

@Composable
private fun Modifier.listingDropChrome(): Modifier {
    val parent = LocalDropParentPath.current
    val hot = dropTargetHot(parent)
    val framed = this
        .reportDropSpot(key = "list:$parent", destinationPath = parent)
        .dropDestinationFrame(hot)
    return if (hot) framed.background(dropTargetTint()) else framed
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
