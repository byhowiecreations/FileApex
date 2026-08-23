package com.fileapex.ui

import com.fileapex.i18n.stringRes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.update.AppUpdateCoordinator
import com.fileapex.update.PendingUpdateOffer
import com.fileapex.update.rememberRequestInstallUnknownAppsPermission

@Composable
fun UpdateAvailableSheet(
    offer: PendingUpdateOffer,
    onDismiss: () -> Unit = { AppUpdateCoordinator.dismissUpdateSheet() }
) {
    val requestInstallPermission = rememberRequestInstallUnknownAppsPermission()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringRes("update_available_title", offer.remoteVersion),
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                offer.releaseTitle
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it != offer.remoteVersion }
                    ?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                Text(
                    text = offer.releaseNotes?.trim()?.takeIf { it.isNotEmpty() }
                        ?: stringRes("update_ready_body"),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    requestInstallPermission()
                    AppUpdateCoordinator.downloadPendingUpdate()
                }
            ) {
                Text(stringRes("download_install"))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    AppUpdateCoordinator.skipPendingUpdate()
                }
            ) {
                Text(stringRes("skip"))
            }
        }
    )
}
