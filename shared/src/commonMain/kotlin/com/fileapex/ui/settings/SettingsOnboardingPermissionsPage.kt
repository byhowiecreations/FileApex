package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.notice.NoticeCoordinator
import com.fileapex.platform.OnboardingPermissionStep
import com.fileapex.ui.OnboardingPermissionCard
import com.fileapex.ui.SettingsScreenLayoutMode
import com.fileapex.ui.StartupPromptGate
import com.fileapex.update.AppUpdateCoordinator

@Composable
internal fun shouldShowOnboardingSetupCard(onboardingSteps: List<OnboardingPermissionStep>): Boolean {
    val settings = FileApexServices.settings
    val updateAnswered by settings.updateCheckPromptShown.collectAsState()
    val updatesEnabled by settings.checkForUpdatesEnabled.collectAsState()
    val noticeChoiceMade by settings.noticeSettings.choiceMade.collectAsState()
    return onboardingSteps.any { !it.granted } ||
        StartupPromptGate.isUpdatePromptPending(
            managedByStore = FileApexServices.managedByStore,
            answered = updateAnswered,
            enabled = updatesEnabled
        ) ||
        StartupPromptGate.isCriticalNoticePending(choiceMade = noticeChoiceMade)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnboardingPermissionsSettingsPage(
    onboardingSteps: List<OnboardingPermissionStep>,
    deniedOnboardingStepIds: Set<String>,
    onGrantOnboardingStep: (String) -> Unit,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit
) {
    SettingsPageShell(
        title = stringRes("onboarding_permissions_title"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        val settings = FileApexServices.settings
        val notices = settings.noticeSettings
        val updateAnswered by settings.updateCheckPromptShown.collectAsState()
        val updatesEnabled by settings.checkForUpdatesEnabled.collectAsState()
        val noticeChoiceMade by notices.choiceMade.collectAsState()
        val updatePending = StartupPromptGate.isUpdatePromptPending(
            managedByStore = FileApexServices.managedByStore,
            answered = updateAnswered,
            enabled = updatesEnabled
        )
        val noticePending = StartupPromptGate.isCriticalNoticePending(choiceMade = noticeChoiceMade)
        val remainingSteps = onboardingSteps.filter { !it.granted }
        if (remainingSteps.isEmpty() && !updatePending && !noticePending) {
            Column(
                modifier = contentModifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringRes("all_permissions_granted"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringRes("all_permissions_granted_desc"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onBack) {
                    Text(stringRes("back_to_settings"))
                }
            }
        } else {
            Column(
                modifier = contentModifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringRes(
                        if (remainingSteps.isNotEmpty()) "onboarding_permissions_subpage_hint" else "post_pair_setup_updates_body"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
                remainingSteps.forEach { step ->
                    OnboardingPermissionCard(
                        step = step,
                        denied = step.id in deniedOnboardingStepIds,
                        onGrant = { onGrantOnboardingStep(step.id) }
                    )
                }
                if (updatePending) {
                    OnboardingChoiceSetupCard(
                        title = stringRes("update_prompt_title"),
                        body = stringRes("update_prompt_body"),
                        acceptLabel = stringRes("update_prompt_yes"),
                        declineLabel = stringRes("update_prompt_no"),
                        onAccept = {
                            settings.setCheckForUpdatesEnabled(true)
                            settings.setUpdateCheckPromptShown(true)
                            AppUpdateCoordinator.onCheckForUpdatesEnabled()
                        },
                        onDecline = {
                            settings.setUpdateCheckPromptShown(true)
                        }
                    )
                }
                if (noticePending) {
                    OnboardingChoiceSetupCard(
                        title = stringRes("notice_consent_title"),
                        body = stringRes("notice_consent_body"),
                        acceptLabel = stringRes("notice_consent_keep"),
                        declineLabel = stringRes("notice_consent_off"),
                        onAccept = {
                            notices.setEnabled(true)
                            notices.markChoiceMade()
                            NoticeCoordinator.onChoiceMade()
                        },
                        onDecline = {
                            notices.setEnabled(false)
                            notices.markChoiceMade()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingChoiceSetupCard(
    title: String,
    body: String,
    acceptLabel: String,
    declineLabel: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) {
                    Text(acceptLabel)
                }
                OutlinedButton(onClick = onDecline) {
                    Text(declineLabel)
                }
            }
        }
    }
}
