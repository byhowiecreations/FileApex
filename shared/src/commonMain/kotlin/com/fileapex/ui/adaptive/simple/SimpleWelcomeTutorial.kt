package com.fileapex.ui.adaptive.simple

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.platform.OnDemandPermissionPolicy
import com.fileapex.ui.StartupPromptGate
import com.fileapex.platform.OnboardingPermissionStep
import com.fileapex.platform.isDesktopHost

object SimpleWelcomeStartupPolicy {
    fun shouldAutoShowTutorialModal(): Boolean = false
}

/**
 * Lightweight first-run tutorial shown on request from Home so new users immediately see
 * the workspace instead of a modal overlay, and can still open the quick tour from "How it works".
 */
@Composable
fun SimpleWelcomeTutorialOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xA6050D11))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .padding(20.dp)
                .widthIn(max = 500.dp)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF102227),
            border = BorderStroke(1.dp, Color(0xFF2B4D54)),
            shadowElevation = 18.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringRes("tutorial_welcome_title"),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF1F7F7)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringRes("tutorial_welcome_subtitle"),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF9EB9BD)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringRes("close"),
                            tint = Color(0xFF9EB9BD)
                        )
                    }
                }

                TutorialTopicRow(
                    icon = Icons.Outlined.PushPin,
                    title = stringRes("tutorial_bulletin_title"),
                    body = stringRes("tutorial_bulletin_body")
                )
                TutorialTopicRow(
                    icon = Icons.Outlined.QrCode2,
                    title = stringRes("tutorial_get_app_title"),
                    body = stringRes(
                        if (isDesktopHost()) "tutorial_get_app_body" else "tutorial_get_app_body_android"
                    )
                )
                TutorialTopicRow(
                    icon = Icons.Outlined.Devices,
                    title = stringRes("tutorial_pair_demo_title"),
                    body = stringRes("tutorial_pair_demo_body")
                )

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2DD4BF),
                        contentColor = Color(0xFF06201D)
                    )
                ) {
                    Text(
                        text = stringRes("tutorial_got_it"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun TutorialTopicRow(
    icon: ImageVector,
    title: String,
    body: String
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF172F35),
        border = BorderStroke(1.dp, Color(0xFF25454B))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Color(0x282DD4BF), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color(0xFF5EEAD4),
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFEDF6F6)
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB5CDD0)
                )
            }
        }
    }
}

@Composable
fun OnDemandLocalFilesPermissionDialog(
    onGrant: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes("local_files_perm_title")) },
        text = { Text(stringRes("local_files_perm_body")) },
        confirmButton = {
            TextButton(onClick = onGrant) {
                Text(stringRes("local_files_perm_grant"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringRes("local_files_perm_later"))
            }
        }
    )
}

@Composable
fun PostPairBackgroundSetupBanner(
    pairedCount: Int,
    onboardingSteps: List<OnboardingPermissionStep>,
    onReviewSetup: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (pairedCount <= 0) return
    val settings = FileApexServices.settings
    val updateAnswered by settings.updateCheckPromptShown.collectAsState()
    val updatesEnabled by settings.checkForUpdatesEnabled.collectAsState()
    val noticeChoiceMade by settings.noticeSettings.choiceMade.collectAsState()
    val dismissed by settings.postPairSetupBannerDismissed.collectAsState()
    val updateCheckPending = StartupPromptGate.isUpdatePromptPending(
        managedByStore = FileApexServices.managedByStore,
        answered = updateAnswered,
        enabled = updatesEnabled
    )
    val criticalNoticePending = StartupPromptGate.isCriticalNoticePending(choiceMade = noticeChoiceMade)
    if (!OnDemandPermissionPolicy.shouldOfferPostPairSetup(
            pairedCount = pairedCount,
            steps = onboardingSteps,
            updateCheckPending = updateCheckPending,
            criticalNoticePending = criticalNoticePending,
            bannerDismissed = dismissed
        )
    ) return
    val hasPermissionSteps = onboardingSteps.any { !it.granted && it.id != OnDemandPermissionPolicy.STORAGE_STEP_ID }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF172F35),
        border = BorderStroke(1.dp, Color(0xFF2B525A))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringRes("post_pair_setup_title"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFEDF6F6)
                )
                Text(
                    text = stringRes(if (hasPermissionSteps) "post_pair_setup_body" else "post_pair_setup_updates_body"),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAEC7CA)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onReviewSetup) {
                    Text(
                        text = stringRes("post_pair_setup_action"),
                        color = Color(0xFF5EEAD4),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                IconButton(onClick = {
                    settings.setPostPairSetupBannerDismissed(true)
                    onDismiss()
                }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringRes("close"),
                        tint = Color(0xFF9EB9BD),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
