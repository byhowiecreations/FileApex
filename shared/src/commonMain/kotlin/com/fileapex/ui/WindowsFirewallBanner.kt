package com.fileapex.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes
import com.fileapex.platform.FirewallInboundStatus
import com.fileapex.platform.allowFirewallInbound
import com.fileapex.platform.firewallInboundStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown on Windows only while the firewall holds a Block rule for this app, or no Allow rule: other devices cannot connect
 * until that is fixed. Checked once when the screen opens and again after the button is used.
 */
@Composable
fun WindowsFirewallBanner(modifier: Modifier = Modifier) {
    var status by remember { mutableStateOf(FirewallInboundStatus.NotApplicable) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { status = withContext(Dispatchers.IO) { firewallInboundStatus() } }
    if (dismissed || (status != FirewallInboundStatus.Blocked && status != FirewallInboundStatus.NoRule)) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringRes("firewall_card_title"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringRes(if (failed) "firewall_card_failed" else "firewall_card_body"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !working,
                    onClick = {
                        working = true
                        failed = false
                        scope.launch {
                            val changed = withContext(Dispatchers.IO) { allowFirewallInbound() }
                            status = withContext(Dispatchers.IO) { firewallInboundStatus() }
                            failed = !changed && status != FirewallInboundStatus.Allowed
                            working = false
                        }
                    }
                ) { Text(stringRes("firewall_card_action")) }
                TextButton(onClick = { dismissed = true }) { Text(stringRes("close")) }
            }
        }
    }
}
