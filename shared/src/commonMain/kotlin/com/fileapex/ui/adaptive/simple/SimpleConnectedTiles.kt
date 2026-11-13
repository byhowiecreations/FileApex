package com.fileapex.ui.adaptive.simple

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.domain.diagnostics.DeviceDiagnosticsFormatter
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.domain.presence.RouteKind
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.DeviceListRow

@Composable
internal fun BrowseActionCard(
    deviceName: String = "",
    onSplitView: () -> Unit,
    modifier: Modifier = Modifier
) {
    val browseTitle = SimpleOverviewCardPolicy.browseDeviceLabel(
        deviceName = deviceName,
        formattedLabel = if (deviceName.isNotBlank()) stringRes("browse_named_device", deviceName) else "",
        fallbackLabel = stringRes("browse_phone")
    )
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSplitView),
        shape = RoundedCornerShape(16.dp),
        color = SimpleModernPalette.CardSurface,
        border = BorderStroke(1.dp, SimpleModernPalette.CardBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Devices,
                    contentDescription = null,
                    tint = SimpleModernPalette.AccentMintSoft,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = browseTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = SimpleModernPalette.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = SimpleModernPalette.PillTint
            ) {
                Text(
                    text = stringRes("split_view_label"),
                    style = MaterialTheme.typography.labelMedium,
                    color = SimpleModernPalette.AccentMintSoft,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
internal fun ClipboardActionCard(
    sharingEnabled: Boolean?,
    promptHint: String?,
    onClick: () -> Unit,
    statusInline: Boolean = true,
    modifier: Modifier = Modifier
) {
    val badgeBg = if (sharingEnabled == true) SimpleModernPalette.StatusGreenPill else SimpleModernPalette.CounterPill
    val badgeInk = if (sharingEnabled == true) SimpleModernPalette.StatusGreenText else SimpleModernPalette.TextSecondary
    val badgeText = when (sharingEnabled) {
        true -> stringRes("simple_clipboard_on")
        false -> stringRes("simple_clipboard_off")
        null -> "-"
    }
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = sharingEnabled == false, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = SimpleModernPalette.CardSurface,
        border = BorderStroke(1.dp, SimpleModernPalette.CardBorder)
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = if (statusInline) 14.dp else 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(if (statusInline) 6.dp else 8.dp)
        ) {
            if (statusInline) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = null,
                            tint = SimpleModernPalette.AccentMintSoft,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringRes("clipboard"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = SimpleModernPalette.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(50), color = badgeBg) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelMedium,
                            color = badgeInk,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentPaste,
                        contentDescription = null,
                        tint = SimpleModernPalette.AccentMintSoft,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringRes("clipboard"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SimpleModernPalette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(shape = RoundedCornerShape(50), color = badgeBg) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeInk,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            if (!promptHint.isNullOrBlank()) {
                Text(
                    text = promptHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = SimpleModernPalette.TextSecondary
                )
            }
        }
    }
}

@Composable
internal fun QuickActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                    color = SimpleModernPalette.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = SimpleModernPalette.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

internal fun buildCompactStatusPill(
    active: DeviceListRow,
    snapshot: PeerDeviceDiagnostics?,
    reachedVia: RouteKind? = null,
    irohPath: String? = null
): String {
    val parts = mutableListOf<String>()
    parts += if (active.online) "Online" else "Offline"
    val interfaceLabel = snapshot?.network?.interfaceType?.takeIf { it.isNotBlank() && it != "Unknown" }
    val transportLabel = when (reachedVia) {
        RouteKind.Tailnet -> "Tailscale"
        RouteKind.Iroh -> if (irohPath == "relay") "Iroh Relay" else "Iroh"
        RouteKind.Lan -> interfaceLabel ?: "LAN"
        null -> interfaceLabel
    }
    if (!transportLabel.isNullOrBlank()) parts += transportLabel
    snapshot?.battery?.levelPercent?.let { parts += "${it}% battery" }
    val total = snapshot?.storage?.totalBytes
    val used = snapshot?.storage?.usedBytes
    if (total != null && used != null && total > used) {
        parts += "${DeviceDiagnosticsFormatter.formatBytes(total - used)} free"
    }
    return parts.joinToString(" · ")
}
