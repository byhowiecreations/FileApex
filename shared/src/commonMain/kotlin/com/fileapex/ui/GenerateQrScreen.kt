package com.fileapex.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Generate QR Code") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    TextButton(onClick = viewModel::refresh) { Text("Refresh") }
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
                text = "Show this code on one device. On the other, scan with the Camera app and tap Open FileApex, " +
                    "or use Add New Device → Scan QR Code. Some Motorola phones require Scan QR Code inside FileApex.",
                modifier = Modifier.padding(bottom = 16.dp)
            )
            state.pairedDeviceName?.let { name ->
                Text(
                    text = "Paired with $name. QR stays active — pair more devices or tap Back when done.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            state.errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
            state.payload?.let { payload ->
                PairingQrPanel(
                    payload = payload,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
