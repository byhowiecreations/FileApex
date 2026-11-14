package com.fileapex.ui.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalThemeIconStyle
import com.fileapex.data.settings.ThemeIconStyle
import com.fileapex.data.settings.traits
import com.fileapex.di.FileApexServices
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.DeviceEntryIcon
import com.fileapex.ui.deviceIconFillsOrb
import com.fileapex.ui.theme.CleanModernPalette
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.ui.theme.FileApexTealDark
import com.fileapex.ui.theme.FluxGlassPalette
import com.fileapex.ui.theme.LocalFileApexUiStyle
import com.fileapex.ui.theme.isFileApexCleanModern

@Composable
internal fun DeviceCardPopOver(
    row: DeviceListRow,
    connecting: Boolean,
    onClick: () -> Unit,
    onRename: (() -> Unit)?,
    onDeviceDetails: (() -> Unit)?,
    onSendClipboard: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val fluent = LocalFileApexUiStyle.current == DesktopUiStyle.WindowsFluent
    val currentTheme = LocalAppTheme.current
    val cleanModern = isFileApexCleanModern()
    val cardShape = if (fluent || cleanModern || currentTheme.traits.fluxSurfaces) RoundedCornerShape(10.dp) else RoundedCornerShape(16.dp)
    val liveStats by TransferActivityGuard.statsFlow.collectAsState()
    val pendingItems by if (FileApexServices.isDatabaseReady()) FileApexServices.transferQueue.pendingItems.collectAsState(initial = emptyList()) else remember { mutableStateOf(emptyList()) }
    val activeSending = pendingItems.firstOrNull { it.isSending }
    val isSendingToThis = liveStats.isActive && activeSending != null && (row.deviceId in activeSending.pendingDeviceIds || activeSending.pendingDeviceNames.any { it.equals(row.title, ignoreCase = true) })

    val isFlux = currentTheme.traits.fluxSurfaces
    val iconStyle = LocalThemeIconStyle.current
    val useStandardBadge = cleanModern || (isFlux && iconStyle == ThemeIconStyle.STANDARD)
    val containerColor = if (isFlux) FluxGlassPalette.deviceCard else if (cleanModern) CleanModernPalette.CardSurface else MaterialTheme.colorScheme.surface
    val textColor = if (isFlux) Color.White else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (isFlux) Color.White.copy(alpha = 0.82f) else MaterialTheme.colorScheme.onSurfaceVariant
    val iconContainerColor = if (useStandardBadge && isFlux) FluxGlassPalette.iconBadgeBg else if (useStandardBadge) CleanModernPalette.IconBadgeBg else if (isFlux) FluxGlassPalette.accentSoft else MaterialTheme.colorScheme.primaryContainer
    val iconTint = if (useStandardBadge && isFlux) FluxGlassPalette.iconBadgeTint else if (useStandardBadge) CleanModernPalette.IconBadgeTint else if (isFlux) FluxGlassPalette.accent else FileApexTealDark
    val borderColor = if (isFlux) FluxGlassPalette.cardBorder else if (cleanModern) CleanModernPalette.CardBorder else FileApexTeal
    val menuIconTint = if (isFlux) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = modifier
            .hoverable(interactionSource)
            .wrapContentWidth()
            .widthIn(min = 180.dp, max = 560.dp)
            .clickable(enabled = !connecting, onClick = onClick),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isFlux) 0.dp else if (cleanModern) 4.dp else 16.dp),
        border = BorderStroke(if (isFlux || cleanModern) 1.dp else 1.5.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .wrapContentWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val badgeShape = if (useStandardBadge) RoundedCornerShape(9.dp) else CircleShape
            Box(
                modifier = Modifier
                    .size(if (useStandardBadge) 38.dp else 44.dp)
                    .clip(badgeShape)
                    .background(iconContainerColor),
                contentAlignment = Alignment.Center
            ) {
                DeviceEntryIcon(
                    row = row,
                    modifier = if (deviceIconFillsOrb(iconStyle)) {
                        Modifier.fillMaxSize().padding(2.dp)
                    } else {
                        Modifier.size(if (useStandardBadge) 20.dp else 24.dp)
                    },
                    tint = iconTint
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(
                modifier = Modifier.width(IntrinsicSize.Max)
            ) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = textColor,
                    softWrap = false,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(3.dp))
                if (isSendingToThis) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val statText = buildList {
                            add(stringRes("sending"))
                            if (liveStats.speedFormatted.isNotBlank()) add(liveStats.speedFormatted)
                            if (liveStats.etaFormatted.isNotBlank()) add(liveStats.etaFormatted)
                            val pct = (liveStats.progress * 100).toInt().coerceIn(0, 100)
                            if (pct in 1..99) add("$pct%")
                        }.joinToString(" • ")
                        Text(
                            text = statText,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (isFlux) FluxGlassPalette.accent else FileApexTeal,
                            softWrap = false
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { liveStats.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp)),
                            color = if (isFlux) FluxGlassPalette.accent else FileApexTeal,
                            trackColor = (if (isFlux) FluxGlassPalette.accent else FileApexTeal).copy(alpha = 0.2f)
                        )
                    }
                } else if (connecting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = if (isFlux) FluxGlassPalette.accent else FileApexTeal
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringRes("connecting"),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isFlux) FluxGlassPalette.accent else FileApexTeal,
                            softWrap = false
                        )
                    }
                } else {
                    Text(
                        text = DeviceListRow.localizedSubtitle(row),
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                        softWrap = true
                    )
                }
            }
            if (onRename != null || onDeviceDetails != null || onSendClipboard != null || onRemove != null) {
                Spacer(modifier = Modifier.width(10.dp))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreHoriz,
                            contentDescription = stringRes("device_options"),
                            tint = menuIconTint
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onRename != null) {
                            DropdownMenuItem(
                                text = { Text(stringRes("rename")) },
                                onClick = {
                                    menuOpen = false
                                    onRename()
                                }
                            )
                        }
                        if (onDeviceDetails != null) {
                            DropdownMenuItem(
                                text = { Text(deviceMenuText(row, "device_details")) },
                                onClick = {
                                    menuOpen = false
                                    onDeviceDetails()
                                }
                            )
                        }
                        if (onSendClipboard != null && !row.isFriend) {
                            DropdownMenuItem(
                                text = { Text(stringRes("send_clipboard")) },
                                onClick = {
                                    menuOpen = false
                                    onSendClipboard()
                                }
                            )
                        }
                        if (onRemove != null) {
                            DropdownMenuItem(
                                text = { Text(deviceMenuText(row, "remove")) },
                                onClick = {
                                    menuOpen = false
                                    onRemove()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
