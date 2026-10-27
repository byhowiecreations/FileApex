package com.fileapex.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.domain.diagnostics.DeviceDetailsDisplayPreferences
import com.fileapex.domain.diagnostics.DeviceDiagnosticsFormatter
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.i18n.stringRes
import com.fileapex.iroh.IrohRuntime
import com.fileapex.presentation.DeviceDetailsState
import com.fileapex.presentation.DeviceListRow

@Composable
internal fun DeviceDetailsDialog(
    details: DeviceDetailsState,
    onDismiss: () -> Unit
) {
    // Nothing saved and still loading: a message said so and the dialog waits for the answer.
    if (details.loading && details.snapshot == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes("device_details_title", details.deviceName), softWrap = true) },
        text = {
            val snapshot = details.snapshot
            if (snapshot == null) {
                Text(text = details.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
                return@AlertDialog
            }
            DeviceDetailsRows(details, snapshot, Modifier.fillMaxWidth().height(320.dp))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringRes("close")) }
        }
    )
}

/** The "Reached by" row shared by the Device Details dialog and the Simple theme's Info page. */
@Composable
internal fun ReachedByField(deviceId: String) {
    val reachedVia by FileApexServices.presenceMonitor.reachedVia.collectAsState()
    val kind = reachedVia[deviceId]
    Column {
        Text(
            text = stringRes("reached_by"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = kind?.let { DeviceListRow.reachedByDetail(it, IrohRuntime.livePath(deviceId)) }
                ?: stringRes("reached_unknown"),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** The scrollable detail rows, shared by the dialog and the Carousel card back. */
@Composable
internal fun DeviceDetailsRows(details: DeviceDetailsState, snapshot: PeerDeviceDiagnostics, modifier: Modifier, bottomInset: androidx.compose.ui.unit.Dp = 0.dp) {
    val displayPreferences by FileApexServices.settings.deviceDetailsDisplayPreferences
        .collectAsState(DeviceDetailsDisplayPreferences.defaults())
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        details.errorMessage?.let {
            Text(text = it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        val updatingLabel = stringRes("device_details_updating")
        ReachedByField(details.deviceId)
        DeviceDiagnosticsFormatter.detailFieldRows(snapshot = snapshot, preferences = displayPreferences)
            .forEach { row ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (details.loading && row.field.changesOverTime) {
                            Spacer(modifier = Modifier.width(6.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp).semantics { contentDescription = updatingLabel },
                                strokeWidth = 1.5.dp
                            )
                        }
                    }
                    Text(text = row.value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        if (bottomInset > 0.dp) Spacer(modifier = Modifier.height(bottomInset))
    }
}
