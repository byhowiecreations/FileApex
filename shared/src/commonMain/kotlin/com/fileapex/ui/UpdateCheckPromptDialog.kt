package com.fileapex.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.update.AppUpdateCoordinator

/**
 * One-time question after setup: should FileApex check GitHub Releases and alert about new versions.
 * The answer is stored with the other settings, so it is asked once and survives restarts and updates.
 */
@Composable
fun UpdateCheckPromptHost() {
    if (FileApexServices.isPlayStoreBuild) return
    val settings = FileApexServices.settings
    val answered by settings.updateCheckPromptShown.collectAsState()
    val enabled by settings.checkForUpdatesEnabled.collectAsState()
    var declined by remember { mutableStateOf(false) }

    // Someone who already turned checking on in Settings has nothing to be asked.
    LaunchedEffect(answered, enabled) {
        if (!answered && enabled) settings.setUpdateCheckPromptShown(true)
    }
    if (!declined && (answered || enabled)) return

    if (declined) {
        AlertDialog(
            onDismissRequest = { declined = false },
            title = { Text(stringRes("update_prompt_title")) },
            text = { Text(stringRes("update_prompt_declined")) },
            confirmButton = { TextButton(onClick = { declined = false }) { Text(stringRes("update_prompt_ok")) } }
        )
        return
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringRes("update_prompt_title")) },
        text = { Text(stringRes("update_prompt_body")) },
        confirmButton = {
            TextButton(onClick = {
                settings.setCheckForUpdatesEnabled(true)
                settings.setUpdateCheckPromptShown(true)
                AppUpdateCoordinator.onCheckForUpdatesEnabled()
            }) { Text(stringRes("update_prompt_yes")) }
        },
        dismissButton = {
            TextButton(onClick = {
                settings.setUpdateCheckPromptShown(true)
                declined = true
            }) { Text(stringRes("update_prompt_no")) }
        }
    )
}
