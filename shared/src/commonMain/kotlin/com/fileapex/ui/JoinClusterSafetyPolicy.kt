package com.fileapex.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.fileapex.i18n.stringRes

/** Guards users against accidentally wiping an active cluster by starting Join Device while paired. */
object JoinClusterSafetyPolicy {
    fun pairedPeerCount(rowsCount: Int, isDemoActive: Boolean): Int = if (isDemoActive) 0 else rowsCount
    fun requiresLeaveConfirmation(pairedCount: Int): Boolean = pairedCount > 0
}

@Composable
fun ConfirmJoinExistingClusterDialog(
    pairedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes("join_existing_cluster_title")) },
        text = { Text(stringRes("join_existing_cluster_message", pairedCount.toString())) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringRes("continue_action"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringRes("cancel"))
            }
        }
    )
}
