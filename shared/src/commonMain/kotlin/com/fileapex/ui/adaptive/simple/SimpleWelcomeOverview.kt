package com.fileapex.ui.adaptive.simple

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes
import com.fileapex.platform.PlatformClipboard
import com.fileapex.platform.isDesktopHost
import io.github.alexzhirkevich.qrose.options.QrErrorCorrectionLevel
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

private const val DOWNLOAD_PAGE_URL = "https://getfileapex.com/download/"

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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
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
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
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
                            modifier = Modifier.weight(1f)
                        )
                        CompanionDownloadPanel()
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ConnectActionsColumn(
                            onAddDevice = onAddDevice,
                            onJoinDevice = onJoinDevice,
                            modifier = Modifier.fillMaxWidth()
                        )
                        CompanionDownloadPanel()
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
            val wideSolo = maxWidth >= 520.dp
            if (wideSolo) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SoloActionTile(
                        title = stringRes("try_interactive_demo"),
                        subtitle = stringRes("explore_fake_devices"),
                        icon = Icons.Outlined.PlayArrow,
                        ctaLabel = stringRes("launch_demo"),
                        onClick = onLaunchDemo,
                        modifier = Modifier.weight(1f)
                    )
                    SoloActionTile(
                        title = stringRes("share_link_title"),
                        subtitle = stringRes("share_link_desc_short"),
                        icon = Icons.Outlined.Link,
                        ctaLabel = null,
                        onClick = onShareAsLink,
                        modifier = Modifier.weight(1f)
                    )
                    SoloActionTile(
                        title = stringRes("bulletin_board"),
                        subtitle = stringRes("bulletin_board_solo_desc"),
                        icon = Icons.Outlined.PushPin,
                        ctaLabel = null,
                        onClick = onOpenBulletinBoard,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoloActionTile(
                        title = stringRes("try_interactive_demo"),
                        subtitle = stringRes("explore_fake_devices"),
                        icon = Icons.Outlined.PlayArrow,
                        ctaLabel = stringRes("launch_demo"),
                        onClick = onLaunchDemo,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SoloActionTile(
                        title = stringRes("share_link_title"),
                        subtitle = stringRes("share_link_desc_short"),
                        icon = Icons.Outlined.Link,
                        ctaLabel = null,
                        onClick = onShareAsLink,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SoloActionTile(
                        title = stringRes("bulletin_board"),
                        subtitle = stringRes("bulletin_board_solo_desc"),
                        icon = Icons.Outlined.PushPin,
                        ctaLabel = null,
                        onClick = onOpenBulletinBoard,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        com.fileapex.friends.ui.FriendsEntryCard()
    }
}

@Composable
private fun ConnectActionsColumn(
    onAddDevice: () -> Unit,
    onJoinDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringRes("connect_first_device"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = SimpleModernPalette.TextPrimary
        )
        Button(
            onClick = onAddDevice,
            modifier = Modifier.fillMaxWidth().height(44.dp),
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
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, SimpleModernPalette.ChromeBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SimpleModernPalette.TextPrimary)
        ) {
            Text(stringRes("join_with_code"), fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CompanionDownloadPanel() {
    if (isDesktopHost()) {
        DownloadQrColumn()
    } else {
        AndroidCompanionDownloadCard()
    }
}

@Composable
private fun DownloadQrColumn() {
    val qrPainter = rememberQrCodePainter(DOWNLOAD_PAGE_URL) {
        errorCorrectionLevel = QrErrorCorrectionLevel.Medium
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(124.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .padding(8.dp)
        ) {
            Image(
                painter = qrPainter,
                contentDescription = stringRes("scan_phone_get_fileapex"),
                modifier = Modifier.size(108.dp)
            )
        }
        Text(
            text = stringRes("scan_phone_get_fileapex"),
            style = MaterialTheme.typography.labelMedium,
            color = SimpleModernPalette.TextPrimary
        )
        Text(
            text = "getfileapex.com/download",
            style = MaterialTheme.typography.labelSmall,
            color = SimpleModernPalette.TextSecondary
        )
    }
}

@Composable
private fun AndroidCompanionDownloadCard() {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SimpleModernPalette.ChromeSurface,
        border = BorderStroke(1.dp, SimpleModernPalette.ChromeBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.LaptopMac,
                contentDescription = null,
                tint = SimpleModernPalette.AccentMintSoft,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = stringRes("desktop_app_get_title"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = SimpleModernPalette.TextPrimary
            )
            Text(
                text = stringRes("desktop_app_get_body"),
                style = MaterialTheme.typography.bodySmall,
                color = SimpleModernPalette.TextSecondary
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { PlatformClipboard.sharePlainText(DOWNLOAD_PAGE_URL, "FileApex") },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, SimpleModernPalette.ChromeBorder),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SimpleModernPalette.AccentMintSoft)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringRes("share_download_link"), style = MaterialTheme.typography.labelMedium)
                }
                IconButton(
                    onClick = { PlatformClipboard.setSystemClipboardText(DOWNLOAD_PAGE_URL) },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringRes("copy"),
                        tint = SimpleModernPalette.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SoloActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    ctaLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = SimpleModernPalette.CardSurface,
        border = BorderStroke(1.dp, SimpleModernPalette.CardBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SimpleModernPalette.AccentMintSoft,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = SimpleModernPalette.TextPrimary
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = SimpleModernPalette.TextSecondary
            )
            if (ctaLabel != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Button(
                    onClick = onClick,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SimpleModernPalette.AccentMint,
                        contentColor = SimpleModernPalette.AccentOnMint
                    )
                ) {
                    Text(ctaLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
