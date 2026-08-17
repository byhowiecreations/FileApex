package com.fileapex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.domain.pairing.LanPairingDiscovery
import com.fileapex.domain.pairing.PairingBeacon
import com.fileapex.domain.pairing.PairingPayload
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.platform.isDesktopHost
import com.fileapex.presentation.DevicesViewModel

private enum class JoinAttemptState {
    Idle,
    Working,
    Failed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinDeviceScreen(
    onBack: () -> Unit,
    viewModel: DevicesViewModel = viewModel { DevicesViewModel() }
) {
    val state by viewModel.uiState.collectAsState()
    var codeText by remember { mutableStateOf("") }
    var codeFromBroadcast by remember { mutableStateOf(false) }
    var qrScanPaused by remember { mutableStateOf(false) }
    var joinAttempt by remember { mutableStateOf(JoinAttemptState.Idle) }
    var joinError by remember { mutableStateOf<String?>(null) }
    var pinText by remember { mutableStateOf("") }

    val discoveredPeers = state.discoveredPairingPeers
    val keypadOnlyEntry = !isDesktopHost()
    val matchedPayload = remember(codeText, discoveredPeers) {
        LanPairingDiscovery.matchInput(codeText, discoveredPeers)
    }
    val working = joinAttempt == JoinAttemptState.Working
    val statusTitle = when {
        matchedPayload != null -> "Code received from ${matchedPayload.deviceName}"
        discoveredPeers.isEmpty() -> "Listening for a nearby pairing broadcast…"
        else -> "Enter the 6-digit code"
    }

    FileApexBackHandler(onBack = onBack, enabled = !working)

    DisposableEffect(Unit) {
        viewModel.startPairingDiscovery()
        onDispose { viewModel.stopPairingDiscovery() }
    }

    LaunchedEffect(discoveredPeers) {
        if (working) return@LaunchedEffect
        val peer = discoveredPeers.firstOrNull() ?: return@LaunchedEffect
        val next = peer.pairingCode
        if (codeText != next) {
            codeText = next
            codeFromBroadcast = true
        }
    }

    LaunchedEffect(state.statusMessage, state.errorMessage, joinAttempt) {
        if (joinAttempt != JoinAttemptState.Working) return@LaunchedEffect
        state.statusMessage?.let { message ->
            if (message.contains("Paired with", ignoreCase = true)) {
                viewModel.dismissMessages()
                onBack()
            }
        }
        state.errorMessage?.let { message ->
            joinAttempt = JoinAttemptState.Failed
            joinError = message
            viewModel.dismissMessages()
        }
    }

    fun beginPairing(payload: PairingPayload) {
        joinError = null
        joinAttempt = JoinAttemptState.Working
        viewModel.pairFromQrPayload(payload)
    }

    fun handleQrText(raw: String) {
        val payload = PairingPayload.parseOrNull(raw)
            ?: PairingPayload.parseFirstOrNull(listOf(raw))
        if (payload != null) {
            qrScanPaused = true
            beginPairing(payload)
        } else {
            qrScanPaused = true
            joinAttempt = JoinAttemptState.Failed
            joinError = PairingPayload.parseFailureMessage(raw)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Join device") },
                navigationIcon = {
                    TextButton(onClick = onBack, enabled = !working) { Text("Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PairingQrScanner(
                onQrText = ::handleQrText,
                enabled = !qrScanPaused && !working,
                modifier = Modifier
                    .weight(0.34f)
                    .fillMaxWidth()
            )

            Surface(
                modifier = Modifier
                    .weight(0.66f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = statusTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (matchedPayload != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )

                    OutlinedTextField(
                        value = formatPairingCodeDisplay(codeText),
                        onValueChange = { incoming ->
                            if (keypadOnlyEntry) return@OutlinedTextField
                            codeText = incoming.take(240)
                            codeFromBroadcast = false
                        },
                        readOnly = keypadOnlyEntry,
                        enabled = !working,
                        label = { Text("Pairing code") },
                        placeholder = { Text("000000") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.headlineSmall.copy(
                            letterSpacing = 4.sp,
                            textAlign = TextAlign.Center
                        )
                    )

                    if (keypadOnlyEntry) {
                        PairingNumericKeypad(
                            onDigit = { digit ->
                                val digits = PairingBeacon.digitsOnly(codeText)
                                if (digits.length < 6) {
                                    codeText = digits + digit
                                    codeFromBroadcast = false
                                }
                            },
                            onBackspace = {
                                val digits = PairingBeacon.digitsOnly(codeText)
                                if (digits.isNotEmpty()) {
                                    codeText = digits.dropLast(1)
                                    codeFromBroadcast = false
                                }
                            },
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    joinError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Button(
                        onClick = {
                            val payload = matchedPayload ?: return@Button
                            beginPairing(payload)
                        },
                        enabled = matchedPayload != null && !working,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (working) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Text("Pairing…")
                            }
                        } else {
                            Text("Confirm")
                        }
                    }
                }
            }
        }
    }

    state.pendingPinPairing?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                pinText = ""
                viewModel.cancelPinPairing()
                joinAttempt = JoinAttemptState.Idle
            },
            title = { Text("Enter device PIN") },
            text = {
                Column {
                    Text(
                        text = "Enter the PIN for ${pending.deviceName} to finish pairing.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { pinText = it.filter { ch -> ch.isDigit() }.take(8) },
                        singleLine = true,
                        label = { Text("PIN") },
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmPinPairing(pinText)
                        pinText = ""
                        joinAttempt = JoinAttemptState.Working
                    },
                    enabled = pinText.isNotBlank()
                ) {
                    Text("Pair")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pinText = ""
                        viewModel.cancelPinPairing()
                        joinAttempt = JoinAttemptState.Idle
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatPairingCodeDisplay(raw: String): String {
    val digits = PairingBeacon.digitsOnly(raw)
    return when {
        digits.isEmpty() -> ""
        digits.length <= 3 -> digits
        else -> "${digits.substring(0, 3)} ${digits.substring(3).take(3)}"
    }
}
