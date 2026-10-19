package com.fileapex.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.iroh.IrohLog
import com.fileapex.iroh.IrohPhase
import com.fileapex.iroh.IrohRelayUrl
import com.fileapex.iroh.IrohRelayUrlCheck
import com.fileapex.iroh.IrohRelayUrlProblem
import com.fileapex.platform.PlatformClipboard
import androidx.compose.ui.text.font.FontFamily
import com.fileapex.iroh.IrohProbeResult
import com.fileapex.iroh.IrohRuntime
import com.fileapex.ui.theme.settingsCardShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun irohRowSubtitle(): String {
    val state by IrohRuntime.state.collectAsState()
    return irohStatusText(state.phase, state.message)
}

@Composable
private fun irohStatusText(phase: IrohPhase, message: String): String = when (phase) {
    IrohPhase.Off -> stringRes("iroh_status_off")
    IrohPhase.Idle -> stringRes("iroh_status_idle")
    IrohPhase.NoNetwork -> stringRes("iroh_status_no_network")
    IrohPhase.Starting -> stringRes("iroh_status_starting")
    IrohPhase.Up -> stringRes("iroh_status_up")
    IrohPhase.Unavailable -> stringRes("iroh_status_unavailable")
    IrohPhase.Failed -> stringRes("iroh_status_failed", message)
}

@Composable
private fun irohReasonText(code: String): String {
    val base = code.substringBefore(':').trim()
    val detail = code.substringAfter(':', "").trim()
    val key = when (base) {
        "direct" -> "iroh_path_direct"
        "relay" -> "iroh_path_relay"
        "iroh_off" -> "iroh_reason_iroh_off"
        "no_lan_route" -> "iroh_reason_no_lan_route"
        "no_pair_key" -> "iroh_reason_no_pair_key"
        "request_failed" -> "iroh_reason_request_failed"
        "peer_has_iroh_off_or_old_build" -> "iroh_reason_peer_off"
        "reply_rejected" -> "iroh_reason_reply_rejected"
        "no_address" -> "iroh_reason_no_address"
        "no_answer" -> "iroh_reason_no_answer"
        "connect_failed" -> "iroh_reason_connect_failed"
        else -> null
    } ?: return code
    val text = stringRes(key)
    return if (detail.isEmpty()) text else "$text ($detail)"
}

@Composable
internal fun IrohSettingsPage(
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit
) {
    val settings = FileApexServices.settings
    val enabled by settings.irohEnabled.collectAsState()
    val advanced by settings.irohAdvancedDebug.collectAsState()
    val restWhenIdle by settings.irohRestWhenIdle.collectAsState()
    val state by IrohRuntime.state.collectAsState()
    val shape = settingsCardShape()
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<PairedDeviceEntity>>(emptyList()) }
    var known by remember { mutableStateOf<Set<String>>(emptySet()) }
    var paths by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val exchange by IrohRuntime.lastExchange.collectAsState()
    val results = remember { mutableStateMapOf<String, IrohProbeResult>() }

    LaunchedEffect(Unit) {
        while (true) {
            devices = FileApexServices.deviceRepository.listDevices().filter { !it.isRemoved }
            known = IrohRuntime.knownPeerIds()
            paths = known.mapNotNull { id -> IrohRuntime.livePath(id)?.let { id to it } }.toMap()
            delay(2_000)
        }
    }

    SettingsPageShell(
        title = stringRes("iroh_title"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            ) {
                Text(
                    text = stringRes("iroh_body"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringRes("iroh_enable"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = irohStatusText(state.phase, state.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IrohRuntime.ownKeyLabel()?.takeIf { advanced }?.let {
                            Text(
                                text = stringRes("iroh_ids_this", IrohRuntime.ownDeviceIdLabel(), it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(checked = enabled, onCheckedChange = { settings.setIrohEnabled(it) })
                }
                if (enabled) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringRes("iroh_rest"), style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = stringRes("iroh_rest_body"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = restWhenIdle, onCheckedChange = { settings.setIrohRestWhenIdle(it) })
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            IrohRelayCard(shape)
            if (state.phase == IrohPhase.Up || state.phase == IrohPhase.Idle) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringRes("iroh_peers"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
                devices.filter { device ->
                    device.deviceId in known || (IrohRuntime.supportsIroh(device) &&
                        exchange[device.deviceId] != IrohRuntime.PEER_WITHOUT_IROH)
                }.forEach { device ->
                    val hasAddress = device.deviceId in known
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = shape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(device.deviceName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = stringRes(if (hasAddress) "iroh_exchanged_yes" else "iroh_exchanged_no"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (advanced) IrohPeerDetails(device, hasAddress, paths, exchange, results, scope) {
                                known = IrohRuntime.knownPeerIds()
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringRes("iroh_advanced"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringRes("iroh_advanced_body"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = advanced, onCheckedChange = { settings.setIrohAdvancedDebug(it) })
                }
            }
            val log by IrohLog.entries.collectAsState()
            if (advanced && log.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = shape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(stringRes("iroh_log"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = log.takeLast(40).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        TextButton(onClick = { PlatformClipboard.setSystemClipboardText(log.joinToString("\n")) }) {
                            Text(stringRes("iroh_log_copy"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IrohPeerDetails(
    device: PairedDeviceEntity,
    hasAddress: Boolean,
    paths: Map<String, String>,
    exchange: Map<String, String>,
    results: MutableMap<String, IrohProbeResult>,
    scope: kotlinx.coroutines.CoroutineScope,
    onExchanged: () -> Unit
) {
    Text(
        text = stringRes("iroh_ids_peer", device.deviceId.take(8), IrohRuntime.peerKeyLabel(device.deviceId) ?: "-"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    paths[device.deviceId]?.takeIf { hasAddress }?.let {
        Text(
            text = stringRes("iroh_address_known") + " (" + irohReasonText(it) + ")",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    exchange[device.deviceId]?.takeIf { it != "ok" }?.let { outcome ->
        Text(
            text = stringRes("iroh_exchange_result", irohReasonText(outcome)),
            style = MaterialTheme.typography.bodySmall
        )
    }
    results[device.deviceId]?.let { result ->
        Text(
            text = if (result.ok && result.path.isNullOrBlank()) {
                stringRes("iroh_test_ok_plain", result.millis.toString())
            } else if (result.ok) {
                stringRes("iroh_test_ok", result.millis.toString(), irohReasonText(result.path.orEmpty()))
            } else {
                stringRes("iroh_test_failed", irohReasonText(result.message))
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            scope.launch {
                IrohRuntime.exchangeWith(device)
                onExchanged()
            }
        }) { Text(stringRes("iroh_exchange")) }
        TextButton(
            enabled = hasAddress,
            onClick = { scope.launch { results[device.deviceId] = IrohRuntime.probe(device.deviceId) } }
        ) { Text(stringRes("iroh_test")) }
    }
}

@Composable
private fun relayProblemText(problem: IrohRelayUrlProblem): String = stringRes(
    when (problem) {
        IrohRelayUrlProblem.Empty -> "iroh_relay_err_empty"
        IrohRelayUrlProblem.NotHttps -> "iroh_relay_err_not_https"
        IrohRelayUrlProblem.IpAddress -> "iroh_relay_err_ip"
        IrohRelayUrlProblem.NoHostName -> "iroh_relay_err_no_host"
        IrohRelayUrlProblem.Malformed -> "iroh_relay_err_malformed"
    }
)

@Composable
private fun IrohRelayCard(shape: androidx.compose.ui.graphics.Shape) {
    val settings = FileApexServices.settings
    val relayOn by settings.irohRelayEnabled.collectAsState()
    val savedUrl by settings.irohRelayUrl.collectAsState()
    var wantsOn by remember { mutableStateOf(relayOn) }
    var url by remember { mutableStateOf(savedUrl) }
    var token by remember { mutableStateOf(settings.irohRelayToken()) }
    val check = IrohRelayUrl.check(url)
    val savedToken = settings.irohRelayToken()
    val changed = !relayOn || (check as? IrohRelayUrlCheck.Valid)?.url != savedUrl || token.trim() != savedToken
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringRes("iroh_relay_title"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = when {
                            relayOn -> stringRes("iroh_relay_in_use", savedUrl)
                            wantsOn -> stringRes("iroh_relay_pending")
                            else -> stringRes("iroh_relay_off")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = wantsOn,
                    onCheckedChange = { on ->
                        wantsOn = on
                        if (!on) settings.saveIrohRelay(false, url, token)
                    }
                )
            }
            if (wantsOn) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringRes("iroh_relay_url_label")) },
                    placeholder = { Text("https://relay.example.com") },
                    singleLine = true,
                    isError = url.isNotBlank() && check is IrohRelayUrlCheck.Invalid,
                    supportingText = {
                        val invalid = (check as? IrohRelayUrlCheck.Invalid)?.takeIf { url.isNotBlank() }
                        Text(invalid?.let { relayProblemText(it.problem) } ?: stringRes("iroh_relay_url_hint"))
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringRes("iroh_relay_token_label")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    enabled = check is IrohRelayUrlCheck.Valid && changed,
                    onClick = {
                        wantsOn = settings.saveIrohRelay(true, url, token)
                        (IrohRelayUrl.check(url) as? IrohRelayUrlCheck.Valid)?.let { url = it.url }
                    }
                ) { Text(stringRes("iroh_relay_save")) }
            }
        }
    }
}
