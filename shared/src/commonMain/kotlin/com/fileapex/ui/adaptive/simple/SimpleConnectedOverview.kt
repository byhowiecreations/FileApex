package com.fileapex.ui.adaptive.simple

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.domain.presence.RouteKind
import com.fileapex.i18n.stringRes
import com.fileapex.platform.isDesktopHost
import com.fileapex.presentation.DeviceListRow

/**
 * Connected device action hub matching the approved `proposed` and `notif-setup` Simple Overviews.
 */
@Composable
internal fun SimpleConnectedOverview(
    active: DeviceListRow,
    snapshot: PeerDeviceDiagnostics?,
    reachedVia: RouteKind?,
    irohPath: String?,
    isRefreshingStatus: Boolean,
    dropHover: Boolean,
    clipboardSharingEnabled: Boolean?,
    clipboardPromptHint: String?,
    onRefreshStatus: () -> Unit,
    onChooseFilesToSend: () -> Unit,
    onBrowseDevice: () -> Unit,
    onOpenSplitView: () -> Unit,
    onShareAsLink: () -> Unit,
    onClipboardAction: () -> Unit,
    onLocatePhone: () -> Unit,
    showDevicesCard: Boolean = false,
    onDevicesAnchor: (Rect) -> Unit = {},
    onOpenDevices: () -> Unit = {},
    notificationContent: @Composable () -> Unit,
    onOpenDeviceDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPhonePeer = !PeerPlatform.isDesktop(active.os, active.platform)
    val statusSummary = buildCompactStatusPill(
        active = active,
        snapshot = snapshot,
        reachedVia = reachedVia,
        irohPath = irohPath
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OverviewAdaptiveHeader(
            deviceName = active.deviceName,
            isRefreshingStatus = isRefreshingStatus,
            onRefreshStatus = onRefreshStatus,
            statusSummary = statusSummary
        )

        UnifiedDropSendCard(
            deviceName = active.deviceName,
            hovered = dropHover,
            enabled = active.online,
            onChooseFiles = onChooseFilesToSend
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val twoColumns = maxWidth >= 420.dp
            if (twoColumns) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val inlineClipboard = SimpleOverviewCardPolicy.clipboardStatusInline(twoColumns = true)
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BrowseActionCard(
                            deviceName = active.deviceName,
                            onSplitView = onOpenSplitView,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        QuickActionTile(
                            title = stringRes("share_link_title"),
                            subtitle = stringRes("share_link_desc_short"),
                            icon = Icons.Outlined.Link,
                            onClick = onShareAsLink,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ClipboardActionCard(
                            sharingEnabled = clipboardSharingEnabled,
                            promptHint = clipboardPromptHint,
                            onClick = onClipboardAction,
                            statusInline = inlineClipboard,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        if (isPhonePeer) {
                            QuickActionTile(
                                title = stringRes("locate_phone"),
                                subtitle = stringRes("ring_device"),
                                icon = Icons.Outlined.LocationOn,
                                onClick = onLocatePhone,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        } else if (showDevicesCard) {
                            QuickActionTile(
                                title = stringRes("devices"),
                                subtitle = stringRes("simple_devices_card_desc"),
                                icon = Icons.Outlined.Link,
                                onClick = onOpenDevices,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .onGloballyPositioned { onDevicesAnchor(it.boundsInWindow()) }
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    if (isPhonePeer && showDevicesCard) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            QuickActionTile(
                                title = stringRes("devices"),
                                subtitle = stringRes("simple_devices_card_desc"),
                                icon = Icons.Outlined.Link,
                                onClick = onOpenDevices,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .onGloballyPositioned { onDevicesAnchor(it.boundsInWindow()) }
                            )
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                val inlineClipboard = SimpleOverviewCardPolicy.clipboardStatusInline(twoColumns = false)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrowseActionCard(
                        deviceName = active.deviceName,
                        onSplitView = onOpenSplitView,
                        modifier = Modifier.fillMaxWidth()
                    )
                    QuickActionTile(
                        title = stringRes("share_link_title"),
                        subtitle = stringRes("share_link_desc_short"),
                        icon = Icons.Outlined.Link,
                        onClick = onShareAsLink,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ClipboardActionCard(
                        sharingEnabled = clipboardSharingEnabled,
                        promptHint = clipboardPromptHint,
                        onClick = onClipboardAction,
                        statusInline = inlineClipboard,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (showDevicesCard) {
                        QuickActionTile(
                            title = stringRes("devices"),
                            subtitle = stringRes("simple_devices_card_desc"),
                            icon = Icons.Outlined.Link,
                            onClick = onOpenDevices,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { onDevicesAnchor(it.boundsInWindow()) }
                        )
                    }
                    if (isPhonePeer) {
                        QuickActionTile(
                            title = stringRes("locate_phone"),
                            subtitle = stringRes("ring_device"),
                            icon = Icons.Outlined.LocationOn,
                            onClick = onLocatePhone,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (isPhonePeer) {
            notificationContent()
        }

        com.fileapex.friends.ui.FriendsEntryCard()

        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onOpenDeviceDetails)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringRes("device_details_stats"),
                style = MaterialTheme.typography.bodySmall,
                color = SimpleModernPalette.TextSecondary
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = SimpleModernPalette.TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun OverviewAdaptiveHeader(
    deviceName: String,
    isRefreshingStatus: Boolean,
    onRefreshStatus: () -> Unit,
    statusSummary: String
) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            OverviewTitleAndDevice(
                deviceName = deviceName,
                isRefreshingStatus = isRefreshingStatus,
                onRefreshStatus = onRefreshStatus
            )
            CompactStatusPill(statusSummary)
        }
    ) { measurables, constraints ->
        val itemConstraints = constraints.copy(minWidth = 0)
        val left = measurables[0].measure(itemConstraints)
        val pill = measurables[1].measure(itemConstraints)
        val inlineGap = 12.dp.roundToPx()
        val stackGap = 8.dp.roundToPx()
        val inline = SimpleHeaderLayoutPolicy.fitsSideBySide(
            availableWidthPx = constraints.maxWidth.toFloat(),
            leftWidthPx = left.width.toFloat(),
            rightWidthPx = pill.width.toFloat(),
            minGapPx = inlineGap.toFloat()
        )
        if (inline) {
            val totalHeight = maxOf(left.height, pill.height)
            layout(constraints.maxWidth, totalHeight) {
                left.placeRelative(0, totalHeight - left.height)
                pill.placeRelative(constraints.maxWidth - pill.width, totalHeight - pill.height)
            }
        } else {
            val totalHeight = left.height + stackGap + pill.height
            layout(constraints.maxWidth, totalHeight) {
                left.placeRelative(0, 0)
                pill.placeRelative(0, left.height + stackGap)
            }
        }
    }
}

@Composable
private fun OverviewTitleAndDevice(
    deviceName: String,
    isRefreshingStatus: Boolean,
    onRefreshStatus: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringRes("simple_overview"),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = SimpleModernPalette.TextPrimary
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = deviceName,
                style = MaterialTheme.typography.bodyMedium,
                color = SimpleModernPalette.TextSecondary
            )
            IconButton(
                onClick = onRefreshStatus,
                enabled = !isRefreshingStatus,
                modifier = Modifier.size(24.dp)
            ) {
                if (isRefreshingStatus) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.6.dp,
                        color = SimpleModernPalette.AccentMintSoft
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = stringRes("refresh"),
                        tint = SimpleModernPalette.TextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactStatusPill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = SimpleModernPalette.ChromeSurface,
        border = BorderStroke(1.dp, SimpleModernPalette.ChromeBorder)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = SimpleModernPalette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun UnifiedDropSendCard(
    deviceName: String,
    hovered: Boolean,
    enabled: Boolean,
    onChooseFiles: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val dashColor = if (hovered) SimpleModernPalette.AccentMint else SimpleModernPalette.AccentMint.copy(alpha = 0.55f)
    val gradient = Brush.verticalGradient(
        colors = if (hovered) {
            listOf(SimpleModernPalette.DropZoneHover, SimpleModernPalette.DropZoneBottom)
        } else {
            listOf(SimpleModernPalette.DropZoneTop, SimpleModernPalette.DropZoneBottom)
        }
    )
    val desktopHost = isDesktopHost()
    val verticalPadding = SimpleOverviewCardPolicy.dropCardVerticalPaddingDp(desktopHost).dp
    val showDropHint = SimpleOverviewCardPolicy.showDropHint(desktopHost)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(gradient)
            .drawBehind {
                val radius = 18.dp.toPx()
                drawRoundRect(
                    color = dashColor,
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(
                        width = if (hovered) 2.2.dp.toPx() else 1.4.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                    )
                )
            }
            .clickable(enabled = enabled, onClick = onChooseFiles)
            .padding(horizontal = 20.dp, vertical = verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (showDropHint) 8.dp else 6.dp)
    ) {
        OutlinedUploadArrowIcon(modifier = Modifier.size(if (showDropHint) 28.dp else 24.dp))
        Text(
            text = stringRes("send_files_to_device", deviceName),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = SimpleModernPalette.TextPrimary
        )
        if (showDropHint) {
            Text(
                text = stringRes("drop_anywhere_or_choose"),
                style = MaterialTheme.typography.bodySmall,
                color = SimpleModernPalette.TextSecondary
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Button(
            onClick = onChooseFiles,
            enabled = enabled,
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = if (showDropHint) 10.dp else 9.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SimpleModernPalette.AccentMint,
                contentColor = SimpleModernPalette.AccentOnMint
            )
        ) {
            Text(stringRes("choose_files"), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun OutlinedUploadArrowIcon(modifier: Modifier = Modifier) {
    val tint = SimpleModernPalette.AccentMintSoft
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val tray = Path().apply {
            moveTo(w * 0.16f, h * 0.62f)
            lineTo(w * 0.16f, h * 0.77f)
            quadraticTo(w * 0.16f, h * 0.85f, w * 0.25f, h * 0.85f)
            lineTo(w * 0.75f, h * 0.85f)
            quadraticTo(w * 0.84f, h * 0.85f, w * 0.84f, h * 0.77f)
            lineTo(w * 0.84f, h * 0.62f)
        }
        drawPath(tray, color = tint, style = stroke)
        val arrow = Path().apply {
            moveTo(w * 0.50f, h * 0.66f)
            lineTo(w * 0.50f, h * 0.17f)
            moveTo(w * 0.30f, h * 0.37f)
            lineTo(w * 0.50f, h * 0.17f)
            lineTo(w * 0.70f, h * 0.37f)
        }
        drawPath(arrow, color = tint, style = stroke)
    }
}
