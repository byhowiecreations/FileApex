package com.fileapex.ui

import com.fileapex.i18n.stringRes

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
    var qrScanPaused by remember { mutableStateOf(false) }
    var joinAttempt by remember { mutableStateOf(JoinAttemptState.Idle) }
    var joinError by remember { mutableStateOf<String?>(null) }
    var pinText by remember { mutableStateOf("") }
    var linkText by remember { mutableStateOf("") }

    val discoveredPeers = state.discoveredPairingPeers
    val working = joinAttempt == JoinAttemptState.Working

    FileApexBackHandler(onBack = onBack, enabled = !working)

    DisposableEffect(Unit) {
        viewModel.startPairingDiscovery()
        onDispose { viewModel.stopPairingDiscovery() }
    }

    LaunchedEffect(state.pairingSucceeded, state.errorMessage, joinAttempt) {
        if (joinAttempt != JoinAttemptState.Working) return@LaunchedEffect
        if (state.pairingSucceeded) {
            viewModel.dismissMessages()
            onBack()
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
        if (com.fileapex.friends.FriendInviteLink.parse(raw) != null) {
            onBack()
            com.fileapex.friends.ui.FriendsUi.openWithInvite(raw)
            return
        }
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
                title = { Text(stringRes("join_device")) },
                navigationIcon = {
                    TextButton(onClick = onBack, enabled = !working) { Text(stringRes("back")) }
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
                        text = if (discoveredPeers.isEmpty()) stringRes("listening_broadcast") else stringRes("join_tap_device"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    discoveredPeers.forEach { peer ->
                        Button(
                            onClick = { beginPairing(peer.toPairingPayload()) },
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${peer.deviceName}  ${peer.ipAddress}")
                        }
                    }

                    if (isDesktopHost()) {
                        OutlinedTextField(
                            value = linkText,
                            onValueChange = { incoming ->
                                linkText = incoming.take(400)
                                PairingPayload.parseOrNull(incoming)?.let { payload ->
                                    linkText = ""
                                    beginPairing(payload)
                                }
                            },
                            enabled = !working,
                            label = { Text(stringRes("paste_pairing_link")) },
                            singleLine = true,
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

                    if (working) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringRes("pairing"))
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
            title = { Text(stringRes("enter_device_pin")) },
            text = {
                Column {
                    Text(
                        text = stringRes("enter_pin_pair", pending.deviceName),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { pinText = it.filter { ch -> ch.isDigit() }.take(8) },
                        singleLine = true,
                        label = { Text(stringRes("pin")) },
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
                    Text(stringRes("pair"))
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
                    Text(stringRes("cancel"))
                }
            }
        )
    }
}
