package com.fileapex.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.i18n.stringRes
import com.fileapex.security.tls.TlsFingerprint
import com.fileapex.security.tls.TlsPinPrompt
import com.fileapex.security.tls.TlsPromptKind

/** Asks the user to compare a peer's key fingerprint with the one shown on that peer. */
@Composable
fun TlsPinPromptDialog(
    prompt: TlsPinPrompt,
    deviceName: String,
    onTrust: () -> Unit,
    onNotNow: () -> Unit
) {
    val changed = prompt.kind == TlsPromptKind.KEY_CHANGED
    val name = deviceName.ifBlank { stringRes("paired_device") }
    AlertDialog(
        onDismissRequest = onNotNow,
        title = {
            Text(
                text = stringRes(if (changed) "tls_pin_changed_title" else "tls_pin_new_title"),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringRes(if (changed) "tls_pin_changed_body" else "tls_pin_new_body", name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = TlsFingerprint.short(prompt.pin),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        },
        confirmButton = { Button(onClick = onTrust) { Text(stringRes("tls_pin_trust")) } },
        dismissButton = { TextButton(onClick = onNotNow) { Text(stringRes("tls_pin_not_now")) } }
    )
}
