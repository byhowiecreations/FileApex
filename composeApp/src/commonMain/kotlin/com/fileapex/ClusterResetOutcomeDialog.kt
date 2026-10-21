package com.fileapex

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fileapex.domain.reset.ClusterResetManager
import com.fileapex.i18n.stringRes

/** The end of a wipe that ran inside the app. Shown at the top of the app, so it appears in every layout, not only the compact one. */
@Composable
internal fun ClusterResetOutcomeDialog(onNavigateHome: () -> Unit) {
    val wiped = ClusterResetManager.outcome.collectAsState().value ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringRes(if (wiped) "leave_cluster_done_title" else "leave_cluster_failed_title")) },
        text = { Text(stringRes(if (wiped) "leave_cluster_done_message" else "leave_cluster_failed_message")) },
        confirmButton = {
            TextButton(
                onClick = {
                    ClusterResetManager.acknowledgeOutcome()
                    if (wiped) onNavigateHome()
                }
            ) { Text(stringRes("ok")) }
        }
    )
}
