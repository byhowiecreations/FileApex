package com.fileapex.ui

import com.fileapex.i18n.stringRes

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
import com.fileapex.domain.transfer.PendingTransferItem
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.presentation.TransferQueueViewModel
import com.fileapex.ui.theme.fileApexChromeContentColor
import com.fileapex.ui.theme.fileApexTopAppBarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferQueueScreen(
    onBack: () -> Unit,
    viewModel: TransferQueueViewModel
) {
    val state by viewModel.uiState.collectAsState()

    FileApexBackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringRes("queued_files")) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringRes("back"), color = fileApexChromeContentColor())
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
            Text(
                text = stringRes("queued_files_intro"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )

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

            if (state.items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No queued files.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
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
    item: PendingTransferItem,
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
                text = if (item.isSending) {
                    "Sending now…"
                } else {
                    "Waiting for: ${item.pendingDeviceNames.joinToString(", ")}"
                },
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
                contentDescription = stringRes("remove_from_queue")
            )
        }
    }
}
