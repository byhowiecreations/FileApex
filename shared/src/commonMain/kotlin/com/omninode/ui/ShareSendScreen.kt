package com.omninode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omninode.domain.share.IncomingSharePayload
import com.omninode.platform.OmniBackHandler
import com.omninode.presentation.ShareSendViewModel
import com.omninode.ui.theme.OmniTeal
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSendScreen(
    payload: IncomingSharePayload,
    onFinished: () -> Unit,
    viewModel: ShareSendViewModel = viewModel(key = payload.sessionId) {
        ShareSendViewModel(payload)
    }
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.sendCompleted) {
        if (state.sendCompleted) {
            delay(600)
            onFinished()
        }
    }

    OmniBackHandler(enabled = !state.isSending) {
        viewModel.cancelCleanup()
        onFinished()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Send with OmniNode") },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            viewModel.cancelCleanup()
                            onFinished()
                        },
                        enabled = !state.isSending
                    ) {
                        Text("Cancel", color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OmniTeal,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 16.dp)
        ) {
            Text(
                text = when {
                    state.fileNames.size == 1 -> state.fileNames.first()
                    state.fileNames.isEmpty() -> "Shared files"
                    else -> "${state.fileNames.size} files"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (state.fileNames.size > 1) {
                Text(
                    text = state.fileNames.take(4).joinToString("\n") +
                        if (state.fileNames.size > 4) "\n…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            state.statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when {
                state.isPreparing -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                text = "Loading devices…",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    }
                }
                state.options.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = "No destinations available. Open OmniNode, pair a device, and try again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    Text(
                        text = "Choose destination devices",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    val listState = rememberLazyListState()
                    val userScrollEnabled by remember {
                        derivedStateOf {
                            listState.canScrollForward || listState.canScrollBackward
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        userScrollEnabled = userScrollEnabled
                    ) {
                        items(
                            items = state.options,
                            key = { it.deviceId }
                        ) { option ->
                            val checked = option.deviceId in state.selectedDeviceIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = checked,
                                        role = Role.Checkbox,
                                        onValueChange = { viewModel.toggleDevice(option.deviceId) }
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(
                                        option.deviceName,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = if (option.isLocal) {
                                            "Save to Downloads/OmniNode"
                                        } else {
                                            "Online · ${option.host}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (state.isSending) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(
                        text = "Sending…",
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }

            Button(
                onClick = viewModel::send,
                enabled = state.selectedDeviceIds.isNotEmpty() &&
                    !state.isSending &&
                    !state.isPreparing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Send")
            }
        }
    }
}
