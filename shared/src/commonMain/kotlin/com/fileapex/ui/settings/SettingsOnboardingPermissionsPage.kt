package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes
import com.fileapex.platform.OnboardingPermissionStep
import com.fileapex.ui.OnboardingPermissionCard
import com.fileapex.ui.SettingsScreenLayoutMode

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
        val remainingSteps = onboardingSteps.filter { !it.granted }
        if (remainingSteps.isEmpty()) {
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
                    text = stringRes("onboarding_permissions_subpage_hint"),
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
            }
        }
    }
}
