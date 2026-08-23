package com.fileapex.ui

import com.fileapex.i18n.stringRes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.domain.pairing.PairingBeacon
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.presentation.GenerateQrViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateQrScreen(
    onBack: () -> Unit,
    viewModel: GenerateQrViewModel = viewModel { GenerateQrViewModel() }
) {
    val state by viewModel.uiState.collectAsState()

    FileApexBackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        viewModel.onScreenEntered()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onScreenLeft() }
    }

    LaunchedEffect(state.pairedDeviceName) {
        if (state.pairedDeviceName != null) {
            kotlinx.coroutines.delay(1500)
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringRes("generate_qr")) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringRes("back")) }
                },
                actions = {
                    if (!state.broadcast.timedOut) {
                        TextButton(onClick = viewModel::retry, enabled = !state.preparingShareServer) {
                            Text(stringRes("refresh"))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = stringRes("qr_intro"),
                modifier = Modifier.padding(bottom = 16.dp)
            )
            state.pairedDeviceName?.let { name ->
                Text(
                    text = stringRes("paired_with", name),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            state.errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
            if (state.preparingShareServer && state.payload != null) {
                Text(
                    text = stringRes("starting_share_server"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            if (state.broadcast.timedOut) {
                Text(
                    text = stringRes("qr_timeout"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Button(
                    onClick = viewModel::retry,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringRes("retry"))
                }
            } else {
                state.payload?.let { payload ->
                    if (state.broadcast.active) {
                        Text(
                            text = stringRes(
                                "broadcasting_on",
                                PairingBeacon.MULTICAST_ADDRESS,
                                PairingBeacon.PORT,
                                1000 / PairingBeacon.BROADCAST_INTERVAL_MS,
                                state.broadcast.remainingLabel
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        )
                    }
                    PairingQrPanel(
                        payload = payload,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
