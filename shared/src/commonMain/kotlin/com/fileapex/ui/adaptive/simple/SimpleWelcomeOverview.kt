package com.fileapex.ui.adaptive.simple

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes
import com.fileapex.platform.isDesktopHost

internal object SimpleWelcomeLayoutPolicy {
    fun showBulletinCard(isDesktop: Boolean): Boolean = isDesktop

    fun soloCardsSideBySide(isDesktop: Boolean, availableWidthDp: Float): Boolean =
        !isDesktop || availableWidthDp >= 520f
}

/**
 * First-run and unpaired landing view inside the Simple theme shell.
 */
@Composable
internal fun SimpleWelcomeOverview(
    onAddDevice: () -> Unit,
    onJoinDevice: () -> Unit,
    onLaunchDemo: () -> Unit,
    onShareAsLink: () -> Unit,
    onOpenBulletinBoard: () -> Unit,
    onHowItWorks: () -> Unit = {},
    isDesktopOverride: Boolean? = null,
    modifier: Modifier = Modifier
) {
    val isDesktop = isDesktopOverride ?: isDesktopHost()
    val showBulletin = SimpleWelcomeLayoutPolicy.showBulletinCard(isDesktop)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (isDesktop) 18.dp else 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringRes("welcome_to_fileapex"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = SimpleModernPalette.TextPrimary
            )
            Text(
                text = stringRes("welcome_subtitle"),
                style = MaterialTheme.typography.bodyMedium,
                color = SimpleModernPalette.TextSecondary
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = SimpleModernPalette.CardSurface,
            border = BorderStroke(1.dp, SimpleModernPalette.AccentMint.copy(alpha = 0.55f))
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(if (isDesktop) 20.dp else 14.dp)) {
                val wideCard = maxWidth >= 460.dp
                if (wideCard) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ConnectActionsColumn(
                            onAddDevice = onAddDevice,
                            onJoinDevice = onJoinDevice,
                            compact = !isDesktop,
                            modifier = Modifier.weight(1f)
                        )
                        CompanionDownloadPanel(isDesktop = isDesktop)
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(if (isDesktop) 16.dp else 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ConnectActionsColumn(
                            onAddDevice = onAddDevice,
                            onJoinDevice = onJoinDevice,
                            compact = !isDesktop,
                            modifier = Modifier.fillMaxWidth()
                        )
                        CompanionDownloadPanel(isDesktop = isDesktop)
                    }
                }
            }
        }

        Text(
            text = stringRes("solo_mode"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = SimpleModernPalette.TextPrimary
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val sideBySide = SimpleWelcomeLayoutPolicy.soloCardsSideBySide(isDesktop, maxWidth.value)
            if (sideBySide) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(if (isDesktop) 12.dp else 10.dp)
                ) {
                    SoloActionTile(
                        title = stringRes("try_interactive_demo"),
                        subtitle = null,
                        icon = Icons.Outlined.PlayArrow,
                        ctaLabel = stringRes("launch_demo"),
                        actionOnRight = isDesktop,
                        compact = !isDesktop,
                        onClick = onLaunchDemo,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    SoloActionTile(
                        title = stringRes("share_link_title"),
                        subtitle = stringRes("share_link_desc_short"),
                        icon = Icons.Outlined.Link,
                        ctaLabel = null,
                        compact = !isDesktop,
                        onClick = onShareAsLink,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    if (showBulletin) {
                        SoloActionTile(
                            title = stringRes("bulletin_board"),
                            subtitle = stringRes("bulletin_board_solo_desc"),
                            icon = Icons.Outlined.PushPin,
                            ctaLabel = null,
                            compact = false,
                            onClick = onOpenBulletinBoard,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoloActionTile(
                        title = stringRes("try_interactive_demo"),
                        subtitle = null,
                        icon = Icons.Outlined.PlayArrow,
                        ctaLabel = stringRes("launch_demo"),
                        actionOnRight = true,
                        compact = false,
                        onClick = onLaunchDemo,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SoloActionTile(
                        title = stringRes("share_link_title"),
                        subtitle = stringRes("share_link_desc_short"),
                        icon = Icons.Outlined.Link,
                        ctaLabel = null,
                        compact = false,
                        onClick = onShareAsLink,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (showBulletin) {
                        SoloActionTile(
                            title = stringRes("bulletin_board"),
                            subtitle = stringRes("bulletin_board_solo_desc"),
                            icon = Icons.Outlined.PushPin,
                            ctaLabel = null,
                            compact = false,
                            onClick = onOpenBulletinBoard,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onHowItWorks,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, SimpleModernPalette.CardBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SimpleModernPalette.AccentMintSoft)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringRes("how_it_works"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
        }

        com.fileapex.friends.ui.FriendsEntryCard()
    }
}

@Composable
private fun ConnectActionsColumn(
    onAddDevice: () -> Unit,
    onJoinDevice: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val buttonHeight = if (compact) 40.dp else 44.dp
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp)) {
        Text(
            text = stringRes("connect_first_device"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = SimpleModernPalette.TextPrimary
        )
        Button(
            onClick = onAddDevice,
            modifier = Modifier.fillMaxWidth().height(buttonHeight),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = SimpleModernPalette.AccentMint,
                contentColor = SimpleModernPalette.AccentOnMint
            )
        ) {
            Text(stringRes("pair_new_device"), fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = onJoinDevice,
            modifier = Modifier.fillMaxWidth().height(buttonHeight),
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, SimpleModernPalette.ChromeBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SimpleModernPalette.TextPrimary)
        ) {
            Text(stringRes("join_with_code"), fontWeight = FontWeight.Medium)
        }
    }
}
