package com.omninode.ui

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
import com.omninode.platform.OmniBackHandler
import com.omninode.presentation.BrowseTarget
import com.omninode.presentation.DeviceListRow
import com.omninode.presentation.ExplorerViewModel
import com.omninode.ui.theme.OmniTeal

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
    viewModel: ExplorerViewModel = viewModel(key = target.deviceId) { ExplorerViewModel(target) }
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val showCopyFabs = state.isSelectionMode && state.selectedFileIds.isNotEmpty() && !state.isMultiCopying
    var pinText by remember { mutableStateOf("") }
    val topBarTitle = titleOverride
        ?: if (target is BrowseTarget.Local) "Local Files" else state.deviceTitle

    OmniBackHandler(enabled = true) {
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(topBarTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = if (state.isSelectionMode) {
                                val count = state.selectedFileIds.size
                                if (count == 0) "Select files" else "$count selected"
                            } else {
                                state.currentPath
                            },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            if (!viewModel.handleBackNavigation()) {
                                onBack()
                            }
                        }
                    ) {
                        Text(
                            when {
                                state.isSelectionMode -> "Cancel"
                                state.canNavigateUp -> "Up"
                                else -> "Devices"
                            }
                        )
                    }
                },
                actions = {
                    if (state.canNavigateUp && !state.isSelectionMode) {
                        TextButton(onClick = onBack) { Text("Devices") }
                    }
                    when {
                        state.isSelectionMode -> {
                            // Copy lives only on the FAB stack — top bar keeps Download (remote).
                            if (state.isRemoteTarget) {
                                TextButton(
                                    onClick = viewModel::downloadSelected,
                                    enabled = state.canDownloadSelection && !state.isDownloading
                                ) {
                                    Text(if (state.isDownloading) "…" else "Download")
                                }
                            }
                        }
                        else -> {
                            TextButton(onClick = { viewModel.enterSelectionMode() }) {
                                Text("Select")
                            }
                            if (state.canPaste) {
                                TextButton(onClick = viewModel::pasteHere) { Text("Paste") }
                            }
                        }
                    }
                }
            )
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
                        text = { Text("Copy") },
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
                        text = { Text("Multi Copy") },
                        containerColor = OmniTeal,
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
                        state.contentDirectories.isEmpty() &&
                        state.contentFiles.isEmpty() -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    else -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (state.isSelectionMode && state.selectedFileIds.isNotEmpty()) {
                                Text(
                                    text = "Copy = save for Paste later · Multi Copy = send now to devices",
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
                                        text = "Ready to paste: ${state.clipboardLabel ?: "file(s)"}",
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 8.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TextButton(onClick = viewModel::pasteHere) {
                                        Text("Paste here")
                                    }
                                }
                            }
                            AdaptiveExplorerView(
                                isWideDisplay = isWide,
                                paneDirectories = state.paneDirectories,
                                contentDirectories = state.contentDirectories,
                                contentFiles = state.contentFiles,
                                selectedFolderPath = state.selectedFolderPath,
                                canNavigateUp = state.canNavigateUp,
                                isSelectionMode = state.isSelectionMode,
                                selectedFileIds = state.selectedFileIds,
                                onNavigateUp = viewModel::navigateUp,
                                onPaneFolderClick = viewModel::onPaneFolderClick,
                                onContentDirectoryClick = viewModel::onContentDirectoryClick,
                                onFileOpen = viewModel::onFileClick,
                                onFileLongPress = viewModel::onFileLongClick,
                                onFileSelectExclusive = viewModel::selectFileExclusive,
                                onFileToggleSelect = viewModel::toggleFileSelectionDesktop,
                                onFileExtendSelect = viewModel::extendFileSelection,
                                onFileActivate = viewModel::activateFile,
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
                            Text("Multi Copy in progress…")
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
            title = { Text("Enter device PIN") },
            text = {
                Column {
                    Text(
                        text = "PIN session expired for ${state.deviceTitle}. Enter the PIN to keep browsing.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { pinText = it.filter { ch -> ch.isDigit() }.take(8) },
                        singleLine = true,
                        label = { Text("PIN") },
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
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pinText = ""
                        viewModel.cancelPinUnlock()
                    }
                ) { Text("Cancel") }
            }
        )
    }

    if (state.showMultiCopyIntro) {
        AlertDialog(
            onDismissRequest = viewModel::dismissMultiCopyIntro,
            title = { Text("Multi Copy") },
            text = {
                Text(
                    "Multi Copy allows you to broadcast the selected file(s) to multiple devices " +
                        "simultaneously. Select your targets on the next screen."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::acknowledgeMultiCopyIntro) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissMultiCopyIntro) {
                    Text("Cancel")
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
                    text = "Multi Copy to devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Files land in Downloads/OmniNode on each selected device. Leave This device unchecked to skip local storage.",
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
                                        "Local device"
                                    } else {
                                        DeviceListRow.peerStatusSubtitle(
                                            online = true,
                                            appVersion = option.appVersion
                                        )
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
                    Text("Multi Copy to Selected Devices")
                }
            }
        }
    }

    val preview = state.previewItem
    if (preview != null || state.isPreviewLoading) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPreview,
            title = { Text(preview?.name ?: "Preview") },
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
                        Text("No preview available.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPreview) { Text("Close") }
            },
            dismissButton = {
                if (state.canDownloadPreview) {
                    TextButton(
                        onClick = viewModel::downloadPreview,
                        enabled = !state.isDownloading && !state.isPreviewLoading
                    ) {
                        Text(if (state.isDownloading) "Downloading…" else "Download")
                    }
                }
            }
        )
    }
}
