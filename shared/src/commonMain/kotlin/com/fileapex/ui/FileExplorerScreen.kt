package com.fileapex.ui

import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes

import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.data.settings.traits
import com.fileapex.ui.theme.KineticStyleLook
import com.fileapex.platform.isDesktopHost
import com.fileapex.platform.openLocalFile
import com.fileapex.platform.revealInFolder
import com.fileapex.ui.DesktopLayoutToggle
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.fileapex.ui.theme.LocalJadedHazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import com.fileapex.ui.adaptive.JadedRaisedTile


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.platform.DownloadsPaths
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.domain.demo.DemoModeState
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.ExplorerActionCopy
import com.fileapex.presentation.ExplorerListOrdering
import com.fileapex.presentation.ExplorerSortMode
import com.fileapex.presentation.ExplorerUiState
import com.fileapex.presentation.ExplorerViewModel
import com.fileapex.util.NetworkUtils
import com.fileapex.ui.adaptive.CompactHomeTitleBand
import com.fileapex.ui.adaptive.CompactHomeTitleStyle
import com.fileapex.ui.theme.FileApexTeal

class ExplorerHeaderCommands(
    val onSelect: () -> Unit,
    val onPaste: (() -> Unit)?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    target: BrowseTarget,
    onBack: () -> Unit,
    /**
     * Optional TopAppBar title override (e.g. "Local Files" in wide list-detail).
     * Compact full-screen explorer keeps [ExplorerUiState.deviceTitle] when null.
     */
    titleOverride: String? = null,
    embeddedInCompactShell: Boolean = false,
    onOpenTransferQueue: () -> Unit = {},
    onRegisterRefresh: (((isRefreshing: Boolean, doRefresh: () -> Unit) -> Unit))? = null,
    onRegisterHeaderCommands: ((ExplorerHeaderCommands?) -> Unit)? = null,
    onExitApp: (() -> Unit)? = null,
    viewModel: ExplorerViewModel = viewModel(key = target.deviceId) { ExplorerViewModel(target) }
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(state.isRefreshing, viewModel) {
        onRegisterRefresh?.invoke(state.isRefreshing, viewModel::refresh)
    }
    LaunchedEffect(state.isSelectionMode, state.canPaste, viewModel) {
        onRegisterHeaderCommands?.invoke(
            if (state.isSelectionMode) {
                null
            } else {
                ExplorerHeaderCommands(
                    onSelect = viewModel::enterSelectionMode,
                    onPaste = if (state.canPaste) viewModel::pasteHere else null
                )
            }
        )
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val showCopyFabs = state.isSelectionMode && state.selectedFileIds.isNotEmpty() && !state.isMultiCopying
    var pinText by remember { mutableStateOf("") }
    val topBarTitle = titleOverride
        ?: if (target is BrowseTarget.Local) stringRes("local_files") else state.deviceTitle

    FileApexBackHandler(enabled = true) {
        when {
            state.showMultiCopyPicker -> viewModel.dismissMultiCopyPicker()
            state.showMultiCopyIntro -> viewModel.dismissMultiCopyIntro()
            !viewModel.handleBackNavigation() -> onBack()
        }
    }

    var filterQuery by remember(state.currentPath) { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(ExplorerSortMode.Name) }
    val visibleDirectories = remember(state.contentDirectories, filterQuery, sortMode) {
        ExplorerListOrdering.apply(state.contentDirectories, filterQuery, sortMode)
    }
    val visibleFiles = remember(state.contentFiles, filterQuery, sortMode) {
        ExplorerListOrdering.apply(state.contentFiles, filterQuery, sortMode)
    }
    val openActionLabel = stringRes("open")
    val showInFolderLabel = stringRes("show_in_folder")
    LaunchedEffect(state.statusMessage, state.errorMessage) {
        state.statusMessage?.let { message ->
            val downloaded = state.lastDownloadedPaths
            if (downloaded.isEmpty()) {
                snackbarHostState.showSnackbar(message)
                viewModel.dismissMessages()
                return@let
            }
            viewModel.dismissMessages()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (downloaded.size == 1) openActionLabel else showInFolderLabel,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                if (downloaded.size == 1) {
                    openLocalFile(downloaded.first())
                } else {
                    revealInFolder(downloaded.first())
                }
            }
        }
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessages()
        }
    }

    val jadedOrbital = LocalAppTheme.current.traits.orbitalHome &&
        LocalKineticStyle.current == KineticStyle.JADED_STEEL
    Scaffold(
        containerColor = when {
            jadedOrbital -> Color.Transparent
            LocalAppTheme.current.traits.orbitalHome && LocalKineticStyle.current == KineticStyle.FROSTED ->
                Color.Transparent
            LocalAppTheme.current.traits.fluxSurfaces -> Color.Transparent
            else -> MaterialTheme.colorScheme.background
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },




        topBar = {
            if (!embeddedInCompactShell) {
                TopAppBar(
                    title = {
                        Column {
                            Text(topBarTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = explorerSubtitle(state),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        ExplorerNavigationAction(
                            state = state,
                            embeddedInCompactShell = false,
                            onNavigate = {
                                if (!viewModel.handleBackNavigation()) {
                                    onBack()
                                }
                            },
                            onBack = onBack
                        )
                    },
                    actions = {
                        ExplorerTopBarActions(
                            state = state,
                            embeddedInCompactShell = false,
                            onBack = onBack,
                            viewModel = viewModel,
                            hasExternalRefresh = onRegisterRefresh != null
                        )
                    },
                    colors = if (jadedOrbital) {
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent,
                            titleContentColor = KineticStyleLook.ink,
                            navigationIconContentColor = KineticStyleLook.ink,
                            actionIconContentColor = KineticStyleLook.steel
                        )
                    } else {
                        TopAppBarDefaults.topAppBarColors()
                    }
                )
            }
        },
        floatingActionButton = {
            if (showCopyFabs) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ExtendedFloatingActionButton(
                        onClick = viewModel::copySelected,
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = null
                            )
                        },
                        text = { Text(ExplorerActionCopy.COPY_ACTION) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    ExtendedFloatingActionButton(
                        onClick = viewModel::onMultiCopyFabClick,
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.CopyAll,
                                contentDescription = null
                            )
                        },
                        text = { Text(ExplorerActionCopy.SEND_TO_ACTION) },
                        containerColor = FileApexTeal,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    ) { padding ->
        val explorerBody: @Composable () -> Unit = {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isWide = maxWidth >= 600.dp
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
                            if (embeddedInCompactShell) {
                                CompactHomeTitleBand(
                                    primaryLine = topBarTitle,
                                    secondaryLine = explorerSubtitle(state),
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
                                            onBack = onBack,
                                            viewModel = viewModel
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
                            if (state.isLoading || state.isRefreshing) {
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
                                }
                            }
                            ExplorerFilterBar(
                                query = filterQuery,
                                onQueryChange = { filterQuery = it },
                                sortMode = sortMode,
                                onSortModeChange = { sortMode = it }
                            )
                            AdaptiveExplorerView(
                                isWideDisplay = isWide,
                                viewMode = state.viewMode,
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
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )
                        }
                    }
                }
                if (state.isLoading && (state.paneDirectories.isNotEmpty() || state.contentDirectories.isNotEmpty() || state.contentFiles.isNotEmpty())) {
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
            }
        }
        if (isDesktopHost()) {
            Box(Modifier.fillMaxSize().padding(padding)) { explorerBody() }
        } else {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) { explorerBody() }
        }
    }

    if (state.pendingPinUnlock) {
        AlertDialog(
            onDismissRequest = {
                pinText = ""
                viewModel.cancelPinUnlock()
            },
            title = { Text(stringRes("enter_device_pin")) },
            text = {
                Column {
                    Text(
                        text = stringRes("pin_session_expired", state.deviceTitle),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { pinText = it.filter { ch -> ch.isDigit() }.take(8) },
                        singleLine = true,
                        label = { Text(stringRes("pin")) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = state.pinUnlockError != null,
                        supportingText = state.pinUnlockError?.let { err ->
                            {
                                Text(err, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmPinUnlock(pinText)
                        pinText = ""
                    },
                    enabled = pinText.isNotBlank()
                ) {
                    Text(stringRes("unlock"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pinText = ""
                        viewModel.cancelPinUnlock()
                    }
                ) { Text(stringRes("cancel")) }
            }
        )
    }

    if (state.showMultiCopyIntro) {
        AlertDialog(
            onDismissRequest = viewModel::dismissMultiCopyIntro,
            title = { Text(ExplorerActionCopy.SEND_TO_INTRO_TITLE) },
            text = {
                Text(ExplorerActionCopy.SEND_TO_INTRO_BODY)
            },
            confirmButton = {
                TextButton(onClick = viewModel::acknowledgeMultiCopyIntro) {
                    Text(stringRes("ok"))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissMultiCopyIntro) {
                    Text(stringRes("cancel"))
                }
            }
        )
    }

    if (state.showMultiCopyPicker) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissMultiCopyPicker,
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    text = ExplorerActionCopy.SEND_TO_PICKER_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringRes("files_land_in", DownloadsPaths.displayLabel()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    state.multiCopyOptions.forEach { option ->
                        val checked = option.deviceId in state.selectedMultiCopyDeviceIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = { viewModel.toggleMultiCopyDevice(option.deviceId) }
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(option.deviceName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = if (option.isLocal) {
                                        stringRes("local_device")
                                    } else {
                                        NetworkUtils.formatEndpointDisplay(option.host, option.port)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = viewModel::confirmMultiCopy,
                    enabled = state.selectedMultiCopyDeviceIds.isNotEmpty() && !state.isMultiCopying,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(ExplorerActionCopy.SEND_TO_PICKER_CONFIRM)
                }
            }
        }
    }

    val preview = state.previewItem
    if (preview != null || state.isPreviewLoading) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPreview,
            title = { Text(preview?.name ?: stringRes("preview")) },
            text = {
                when {
                    state.isPreviewLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    state.previewImage != null -> {
                        Image(
                            bitmap = state.previewImage!!,
                            contentDescription = preview?.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 480.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    state.previewText != null -> {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(state.previewText!!)
                        }
                    }
                    else -> {
                        Text(stringRes("no_preview"))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPreview) { Text(stringRes("close")) }
            },
            dismissButton = {
                if (state.canDownloadPreview) {
                    TextButton(
                        onClick = viewModel::downloadPreview,
                        enabled = !state.isDownloading && !state.isPreviewLoading
                    ) {
                        Text(if (state.isDownloading) stringRes("downloading") else stringRes("download"))
                    }
                }
            }
        )
    }
}

private fun explorerSubtitle(state: ExplorerUiState): String =
    if (state.isSelectionMode) {
        val count = state.selectedFileIds.size
        if (count == 0) AppI18n.t("select_files") else AppI18n.plural("selected_count", count)
    } else {
        state.currentPath
    }

@Composable
private fun ExplorerNavigationAction(
    state: ExplorerUiState,
    embeddedInCompactShell: Boolean,
    onNavigate: () -> Unit,
    onBack: () -> Unit
) {
    val jadedOrbital = LocalAppTheme.current.traits.orbitalHome &&
        LocalKineticStyle.current == KineticStyle.JADED_STEEL
    val label = when {
        state.isSelectionMode -> stringRes("cancel")
        state.canNavigateUp -> stringRes("up")
        embeddedInCompactShell -> null
        else -> null
    }
    if (label != null) {
        TextButton(onClick = onNavigate) {
            Text(
                label,
                color = if (jadedOrbital) KineticStyleLook.steel else Color.Unspecified
            )
        }
    }
}

@Composable
private fun ExplorerTopBarActions(
    state: ExplorerUiState,
    embeddedInCompactShell: Boolean,
    onBack: () -> Unit,
    viewModel: ExplorerViewModel,
    hasExternalRefresh: Boolean = false
) {
    val jadedOrbital = LocalAppTheme.current.traits.orbitalHome &&
        LocalKineticStyle.current == KineticStyle.JADED_STEEL
    when {
        state.isSelectionMode -> {
            if (state.isRemoteTarget) {
                TextButton(
                    onClick = viewModel::downloadSelected,
                    enabled = state.canDownloadSelection && !state.isDownloading
                ) {
                    Text(
                        if (state.isDownloading) "…" else stringRes("download"),
                        color = if (jadedOrbital) KineticStyleLook.steel else Color.Unspecified
                    )
                }
            }
            if (embeddedInCompactShell && isDesktopHost()) {
                DesktopLayoutToggle()
            }
        }
        else -> {
            if (!hasExternalRefresh) {
                TextButton(onClick = { viewModel.enterSelectionMode() }) {
                    Text(
                        text = stringRes("select"),
                        color = if (jadedOrbital) KineticStyleLook.steel else Color.Unspecified
                    )
                }
                if (state.canPaste) {
                    TextButton(onClick = viewModel::pasteHere) {
                        Text(
                            text = stringRes("paste"),
                            color = if (jadedOrbital) KineticStyleLook.steel else Color.Unspecified
                        )
                    }
                }
            }
            if (embeddedInCompactShell) {
                ExplorerViewModeToggle(
                    viewMode = state.viewMode,
                    onToggle = viewModel::toggleViewMode
                )
                if (isDesktopHost()) {
                    DesktopLayoutToggle()
                }
            }
            if (!hasExternalRefresh) {
                if (jadedOrbital) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(enabled = !state.isRefreshing, onClick = viewModel::refresh),
                        contentAlignment = Alignment.Center
                    ) {
                        JadedRaisedTile(tileSize = 28.dp) {
                            if (state.isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = KineticStyleLook.steel
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = stringRes("refresh"),
                                    tint = KineticStyleLook.steel,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                } else {
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !state.isRefreshing
                    ) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringRes("refresh"),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun ExplorerFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    sortMode: ExplorerSortMode,
    onSortModeChange: (ExplorerSortMode) -> Unit
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val jadedField = LocalAppTheme.current.traits.orbitalHome &&
            LocalKineticStyle.current == KineticStyle.JADED_STEEL
        if (jadedField) {
            val pill = RoundedCornerShape(50)
            val ink = Color(0xFFE4EEEF)
            val hint = Color(0xFFB7C4C6)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = ink),
                cursorBrush = SolidColor(ink),
                modifier = Modifier.weight(1f).height(44.dp),
                decorationBox = { inner ->
                    val haze = LocalJadedHazeState.current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(pill)
                            .then(
                                if (haze != null) {
                                    Modifier.hazeEffect(
                                        state = haze,
                                        style = HazeStyle(
                                            backgroundColor = Color.Transparent,
                                            tints = listOf(HazeTint(Color.White.copy(alpha = 0.09f))),
                                            blurRadius = 24.dp,
                                            noiseFactor = 0.04f
                                        )
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .background(Color.White.copy(alpha = 0.09f))
                            .border(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    0f to Color.White.copy(alpha = 0.28f),
                                    1f to Color.White.copy(alpha = 0.10f)
                                ),
                                shape = pill
                            )
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = hint)
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    text = stringRes("filter_this_folder"),
                                    color = hint,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            inner()
                        }
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Filled.Close, contentDescription = stringRes("clear"), tint = hint)
                            }
                        }
                    }
                }
            )
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringRes("filter_this_folder")) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringRes("clear"))
                        }
                    }
                },
                textStyle = MaterialTheme.typography.bodyMedium
            )
        }
        Box {
            IconButton(onClick = { sortMenuOpen = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = stringRes("sort_by"),
                    tint = when {
                        jadedField -> KineticStyleLook.steel
                        LocalAppTheme.current.traits.glassChrome -> Color.White
                        else -> LocalContentColor.current
                    }
                )
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                ExplorerSortMode.entries.forEach { mode ->
                    val label = when (mode) {
                        ExplorerSortMode.Name -> stringRes("sort_name")
                        ExplorerSortMode.Date -> stringRes("sort_date")
                        ExplorerSortMode.Size -> stringRes("sort_size")
                    }
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = {
                            if (mode == sortMode) Icon(Icons.Filled.Check, contentDescription = null)
                        },
                        onClick = {
                            onSortModeChange(mode)
                            sortMenuOpen = false
                        }
                    )
                }
            }
        }
    }
}

