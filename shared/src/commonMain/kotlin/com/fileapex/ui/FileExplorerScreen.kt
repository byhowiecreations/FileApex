package com.fileapex.ui

import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes

import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image


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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.ExplorerActionCopy
import com.fileapex.presentation.ExplorerUiState
import com.fileapex.presentation.ExplorerViewModel
import com.fileapex.util.NetworkUtils
import com.fileapex.ui.adaptive.CompactHomeTitleBand
import com.fileapex.ui.adaptive.CompactHomeTitleStyle
import com.fileapex.ui.theme.FileApexTeal

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
    viewModel: ExplorerViewModel = viewModel(key = target.deviceId) { ExplorerViewModel(target) }
) {
    val state by viewModel.uiState.collectAsState()
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

    LaunchedEffect(state.statusMessage, state.errorMessage) {
        state.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessages()
        }
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessages()
        }
    }

    Scaffold(
        containerColor = if (LocalAppTheme.current == AppTheme.FLUX_GLASS) Color.Transparent else MaterialTheme.colorScheme.background,
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
                            viewModel = viewModel
                        )
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
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
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
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TextButton(onClick = viewModel::pasteHere) {
                                        Text(stringRes("paste_here"))
                                    }
                                }
                            }
                            AdaptiveExplorerView(
                                isWideDisplay = isWide,
                                viewMode = state.viewMode,
                                panePath = state.panePath,
                                paneDirectories = state.paneDirectories,
                                contentDirectories = if (isWide) {
                                    state.contentDirectories
                                } else if (state.currentPath.isBlank() ||
                                    state.currentPath == state.panePath
                                ) {
                                    state.paneDirectories
                                } else {
                                    state.contentDirectories
                                },
                                contentFiles = if (isWide) {
                                    state.contentFiles
                                } else if (state.currentPath.isBlank() ||
                                    state.currentPath == state.panePath
                                ) {
                                    state.paneFiles
                                } else {
                                    state.contentFiles
                                },
                                selectedFolderPath = state.selectedFolderPath,
                                canNavigateUp = state.canNavigateUp,
                                isSelectionMode = state.isSelectionMode,
                                selectedFileIds = state.selectedFileIds,
                                isRemoteTarget = state.isRemoteTarget,
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
    val label = when {
        state.isSelectionMode -> stringRes("cancel")
        state.canNavigateUp -> stringRes("up")
        embeddedInCompactShell -> null
        else -> stringRes("devices")
    }
    if (label != null) {
        TextButton(onClick = onNavigate) {
            Text(label)
        }
    }
}

@Composable
private fun ExplorerTopBarActions(
    state: ExplorerUiState,
    embeddedInCompactShell: Boolean,
    onBack: () -> Unit,
    viewModel: ExplorerViewModel
) {
    if (!embeddedInCompactShell && state.canNavigateUp && !state.isSelectionMode) {
        TextButton(onClick = onBack) { Text(stringRes("devices")) }
    }
    when {
        state.isSelectionMode -> {
            if (state.isRemoteTarget) {
                TextButton(
                    onClick = viewModel::downloadSelected,
                    enabled = state.canDownloadSelection && !state.isDownloading
                ) {
                    Text(if (state.isDownloading) "…" else stringRes("download"))
                }
            }
        }
        else -> {
            if (embeddedInCompactShell) {
                ExplorerViewModeToggle(
                    viewMode = state.viewMode,
                    onToggle = viewModel::toggleViewMode
                )
            }
            TextButton(onClick = { viewModel.enterSelectionMode() }) {
                Text(stringRes("select"))
            }
            if (state.canPaste) {
                TextButton(onClick = viewModel::pasteHere) { Text(stringRes("paste")) }
            }
        }

    }
}
