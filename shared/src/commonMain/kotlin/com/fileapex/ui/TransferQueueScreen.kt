package com.fileapex.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.presentation.TransferQueueViewModel
import com.fileapex.ui.dnd.deviceFileDropTarget
import com.fileapex.ui.theme.fileApexChromeContentColor
import com.fileapex.ui.theme.fileApexTopAppBarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferQueueScreen(
    onBack: () -> Unit,
    viewModel: TransferQueueViewModel = viewModel { TransferQueueViewModel() }
) {
    val state by viewModel.uiState.collectAsState()
    val desktopDrop = usesDesktopFileSelection()

    FileApexBackHandler { onBack() }

    if (state.showDevicePicker) {
        QueueAddDevicePickerDialog(
            deviceOptions = state.deviceOptions,
            selectedDeviceIds = state.selectedDeviceIds,
            isLoading = state.isLoadingDevices,
            onToggleDevice = viewModel::toggleDevice,
            onDismiss = viewModel::dismissDevicePicker,
            onConfirm = viewModel::confirmEnqueueDropped
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Queued Files") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back", color = fileApexChromeContentColor())
                    }
                },
                colors = fileApexTopAppBarColors()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
        ) {
            if (desktopDrop) {
                Text(
                    text = "Drop files here to queue for a device when it returns to local Wi‑Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                Text(
                    text = "Files send automatically when the destination is back on local Wi‑Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            state.statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            val listModifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(
                    if (desktopDrop) {
                        Modifier.deviceFileDropTarget(
                            onHoverChange = {},
                            onFilesDropped = viewModel::onDesktopFilesDropped
                        )
                    } else {
                        Modifier
                    }
                )

            if (state.items.isEmpty()) {
                Box(
                    modifier = listModifier,
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (desktopDrop) {
                            "No queued files. Drop files here to add."
                        } else {
                            "No queued files."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = listModifier,
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.items, key = { it.id }) { item ->
                        QueuedTransferRow(
                            item = item,
                            onRemove = { viewModel.remove(item.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuedTransferRow(
    item: com.fileapex.domain.transfer.PendingTransferItem,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayLabel,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Waiting for: ${item.pendingDeviceNames.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            item.lastError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Remove from queue"
            )
        }
    }
}
