package com.fileapex.ui.notice

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fileapex.di.FileApexServices
import com.fileapex.domain.reset.ClusterResetManager
import com.fileapex.i18n.stringRes
import com.fileapex.notice.NoticeCoordinator

/**
 * The one-time explanation of Critical Notifications, on a new install after setup and on the first start of an
 * existing install after the update that adds it. No request is made until it has been shown. Closing it without
 * choosing keeps the setting on. Wipe Data clears the flag, so a fresh start asks again.
 */
@Composable
fun CriticalNoticeConsentHost() {
    val settings = FileApexServices.settings
    val notices = settings.noticeSettings
    val choiceMade by notices.choiceMade.collectAsState()
    val updatePromptShown by settings.updateCheckPromptShown.collectAsState()
    val checkingForUpdates by settings.checkForUpdatesEnabled.collectAsState()
    val wiping by ClusterResetManager.inProgress.collectAsState()
    val wipeResult by ClusterResetManager.outcome.collectAsState()
    if (choiceMade || wiping || wipeResult != null) return
    // The update question comes first where it exists, so two dialogs never stack.
    if (!FileApexServices.managedByStore && !updatePromptShown && !checkingForUpdates) return

    fun answer(enabled: Boolean) {
        notices.setEnabled(enabled)
        notices.markChoiceMade()
        if (enabled) NoticeCoordinator.onChoiceMade()
    }
    AlertDialog(
        onDismissRequest = { answer(true) },
        title = { Text(stringRes("notice_consent_title")) },
        text = { Text(stringRes("notice_consent_body")) },
        confirmButton = { TextButton(onClick = { answer(true) }) { Text(stringRes("notice_consent_keep")) } },
        dismissButton = { TextButton(onClick = { answer(false) }) { Text(stringRes("notice_consent_off")) } }
    )
}
