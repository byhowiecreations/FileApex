package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.cloud.device.DeviceIdentity
import com.fileapex.i18n.stringRes
import com.fileapex.platform.rememberGoogleSignInLauncher

/**
 * Shown while the relay holds an older FileApex setup of this Google account (the devices were set up again). Starting
 * over needs a fresh Google sign-in; the relay then switches every older device off. Plan:
 * docs/planning/one-trust-path-plan.md, item 9.
 */
@Composable
internal fun StartOverSection(onStartOverIdToken: (idToken: String?, email: String?, errorMessage: String?) -> Unit) {
    val olderSetup by DeviceIdentity.otherSetup.collectAsState()
    if (!olderSetup) return
    var confirming by remember { mutableStateOf(false) }
    val signInAgain = rememberGoogleSignInLauncher(onResult = onStartOverIdToken)
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringRes("start_over_title"), style = MaterialTheme.typography.titleSmall)
        Text(
            stringRes("start_over_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = { confirming = true }, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringRes("start_over_button"))
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringRes("start_over_confirm_title")) },
            text = { Text(stringRes("start_over_confirm_text")) },
            confirmButton = {
                TextButton(onClick = { confirming = false; signInAgain() }) { Text(stringRes("start_over_confirm")) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringRes("cancel")) } }
        )
    }
}
