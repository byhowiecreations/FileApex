package com.fileapex.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fileapex.platform.OemBackgroundGuidance

/**
 * First-run setup: all-files access (required) + unrestricted battery (recommended on Android).
 */
@Composable
fun StoragePermissionScreen(
    hasStoragePermission: Boolean,
    hasUnrestrictedBattery: Boolean,
    oemBackgroundGuidance: OemBackgroundGuidance? = null,
    onRequestStoragePermission: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onOpenBackgroundPersistenceSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "FileApex setup",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Grant these permissions so FileApex can share files reliably on your Wi‑Fi.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "1. All-files access",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (hasStoragePermission) {
                "Granted"
            } else {
                "Required to browse and share local folders with paired devices."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        if (!hasStoragePermission) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onRequestStoragePermission,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant file access")
            }
            TextButton(onClick = onOpenStorageSettings) {
                Text("Open system settings")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "2. Background battery (recommended)",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = when {
                hasUnrestrictedBattery -> "Granted"
                oemBackgroundGuidance != null ->
                    "Recommended so background file sharing is not killed. On your " +
                        "${oemBackgroundGuidance.vendorLabel} phone set: " +
                        "${oemBackgroundGuidance.appBatteryUsageSteps}."
                else ->
                    "Recommended so background file sharing is not killed by battery " +
                        "optimization. Set App battery usage to Unrestricted or Always allow."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        if (hasStoragePermission && !hasUnrestrictedBattery) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpenBackgroundPersistenceSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open battery settings")
            }
            TextButton(onClick = onRequestBatteryUnrestricted) {
                Text("Request battery exemption")
            }
        }
    }
}
