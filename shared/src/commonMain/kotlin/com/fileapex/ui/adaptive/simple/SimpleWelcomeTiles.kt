package com.fileapex.ui.adaptive.simple

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.LaptopMac
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
import io.github.alexzhirkevich.qrose.options.QrErrorCorrectionLevel
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

private const val DOWNLOAD_PAGE_URL = "https://getfileapex.com/download/"

@Composable
internal fun CompanionDownloadPanel(isDesktop: Boolean) {
    if (isDesktop) {
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
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.LaptopMac,
                contentDescription = null,
                tint = SimpleModernPalette.AccentMintSoft,
                modifier = Modifier.size(26.dp)
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
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
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
                    modifier = Modifier.size(32.dp)
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
internal fun SoloActionTile(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    ctaLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    actionOnRight: Boolean = false,
    compact: Boolean = false
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = SimpleModernPalette.CardSurface,
        border = BorderStroke(1.dp, SimpleModernPalette.CardBorder)
    ) {
        if (actionOnRight && ctaLabel != null) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.weight(1f),
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
                    Text(
                        text = ctaLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxHeight().padding(if (compact) 11.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = SimpleModernPalette.AccentMintSoft,
                        modifier = Modifier.size(if (compact) 16.dp else 18.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SimpleModernPalette.TextPrimary,
                        maxLines = 2
                    )
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = SimpleModernPalette.TextSecondary,
                        maxLines = if (compact) 2 else Int.MAX_VALUE
                    )
                }
                if (ctaLabel != null) {
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = onClick,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SimpleModernPalette.AccentMint,
                            contentColor = SimpleModernPalette.AccentOnMint
                        )
                    ) {
                        Text(
                            text = ctaLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}
