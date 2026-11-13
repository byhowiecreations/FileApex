package com.fileapex.ui.adaptive.simple

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.DeviceListRow
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.ui.adaptive.SimpleDestination
import kotlin.math.roundToInt
import com.fileapex.ui.adaptive.SimplePopover
import com.fileapex.ui.adaptive.SimpleRailEntry
import com.fileapex.ui.dialogs.ClipboardTargetConfigDialog

@Composable
internal fun SimpleHomeDialogs(
    devicesViewModel: DevicesViewModel,
    rows: List<DeviceListRow>,
    active: DeviceListRow?,
    showSwitchDialog: Boolean,
    onDismissSwitch: () -> Unit,
    confirmRemove: Boolean,
    onDismissRemove: () -> Unit,
    onAfterRemove: () -> Unit,
    showOtherThemesHint: Boolean,
    onDismissOtherThemesHint: () -> Unit
) {
    val settings = FileApexServices.settings
    val state by devicesViewModel.uiState.collectAsState()

    if (showSwitchDialog) {
        AlertDialog(
            onDismissRequest = onDismissSwitch,
            title = { Text(stringRes("simple_choose_device")) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    rows.forEach { row ->
                        ListItem(
                            modifier = Modifier.clickable {
                                settings.setSimpleActiveDeviceId(row.deviceId)
                                onDismissSwitch()
                            },
                            headlineContent = {
                                Text(
                                    row.deviceName,
                                    fontWeight = if (row.deviceId == active?.deviceId) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            supportingContent = { Text(row.subtitle) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismissSwitch) { Text(stringRes("close")) } }
        )
    }

    val removeTarget = active
    if (confirmRemove && removeTarget != null) {
        AlertDialog(
            onDismissRequest = onDismissRemove,
            title = { Text(stringRes("remove_device")) },
            text = { Text(stringRes("remove_device_body", removeTarget.deviceName)) },
            confirmButton = {
                TextButton(onClick = {
                    devicesViewModel.removeDevice(removeTarget.deviceId)
                    onDismissRemove()
                    onAfterRemove()
                }) { Text(stringRes("remove")) }
            },
            dismissButton = { TextButton(onClick = onDismissRemove) { Text(stringRes("cancel")) } }
        )
    }

    if (showOtherThemesHint) {
        AlertDialog(
            onDismissRequest = onDismissOtherThemesHint,
            title = { Text(stringRes("simple_other_themes_title")) },
            text = { Text(stringRes("simple_other_themes_body")) },
            confirmButton = { TextButton(onClick = onDismissOtherThemesHint) { Text(stringRes("simple_got_it")) } }
        )
    }

    if (state.showClipboardConfigDialog) {
        val currentMode by settings.clipboardShareMode.collectAsState()
        val currentTargets by settings.clipboardTargetDeviceIds.collectAsState()
        ClipboardTargetConfigDialog(
            initialMode = currentMode,
            initialTargetIds = currentTargets,
            peers = state.clipboardConfigPeers,
            onConfirm = { mode, targets -> devicesViewModel.confirmClipboardConfig(mode, targets) },
            onDismiss = devicesViewModel::dismissClipboardConfigDialog
        )
    }
}

@Composable
internal fun SimpleHomePopoverLayer(
    popover: SimplePopover?,
    lastPopover: SimplePopover,
    menuAnchor: Rect?,
    overlayOrigin: Offset,
    anchoredBelow: Boolean,
    rows: List<DeviceListRow>,
    active: DeviceListRow?,
    hiddenEntries: Set<SimpleRailEntry>,
    onChangePopover: (SimplePopover?) -> Unit,
    onNavigate: (SimpleDestination) -> Unit,
    onLocateActive: (DeviceListRow) -> Unit,
    onSwitchDevice: () -> Unit,
    onGenerateQr: () -> Unit,
    onJoinDevice: () -> Unit,
    onConfirmRemove: () -> Unit,
    onSendClipboard: () -> Unit,
    onOpenNotes: () -> Unit
) {
    SimplePopoverHost(
        visible = popover != null,
        anchor = menuAnchor,
        overlayOrigin = overlayOrigin,
        anchoredBelow = anchoredBelow,
        onDismiss = { onChangePopover(null) }
    ) {
        when (popover ?: lastPopover) {
            SimplePopover.DeviceManagement -> {
                if (active != null) SimpleManagementItem(Icons.Filled.Info, stringRes("info")) { onChangePopover(null); onNavigate(SimpleDestination.Info) }
                if (active != null && active.online && PeerPlatform.isAndroid(active.os, active.platform)) {
                    SimpleManagementItem(Icons.Filled.LocationOn, stringRes("locate_phone")) {
                        onChangePopover(null)
                        onLocateActive(active)
                    }
                }
                if (rows.size >= 2) SimpleManagementItem(Icons.Filled.SwapHoriz, stringRes("simple_switch_device")) { onChangePopover(null); onSwitchDevice() }
                SimpleManagementItem(Icons.Filled.Add, stringRes("add_new_device")) { onChangePopover(null); onGenerateQr() }
                SimpleManagementItem(Icons.Filled.Link, stringRes("join_device")) { onChangePopover(null); onJoinDevice() }
                SimpleManagementItem(Icons.Filled.People, stringRes("friends_entry")) { onChangePopover(null); com.fileapex.friends.ui.FriendsUi.open() }
                if (active != null) SimpleManagementItem(Icons.Filled.Delete, stringRes("remove"), danger = true) { onChangePopover(null); onConfirmRemove() }
            }
            SimplePopover.More -> {
                if (SimpleRailEntry.LocalFiles in hiddenEntries) SimpleManagementItem(Icons.Filled.Folder, stringRes("local_files")) { onChangePopover(null); onNavigate(SimpleDestination.LocalFiles) }
                if (SimpleRailEntry.Browse in hiddenEntries) SimpleManagementItem(Icons.Filled.Devices, stringRes("browse")) { onChangePopover(null); onNavigate(SimpleDestination.Browse) }
                if (SimpleRailEntry.Clipboard in hiddenEntries) SimpleManagementItem(Icons.Filled.ContentPaste, stringRes("send_clipboard")) { onChangePopover(null); onSendClipboard() }
                if (SimpleRailEntry.BulletinBoard in hiddenEntries) SimpleManagementItem(Icons.Outlined.PushPin, stringRes("bulletin_board")) { onChangePopover(null); onOpenNotes() }
                if (SimpleRailEntry.DeviceManagement in hiddenEntries) SimpleManagementItem(Icons.Filled.Link, stringRes("simple_device_management")) { onChangePopover(SimplePopover.DeviceManagement) }
                if (SimpleRailEntry.Settings in hiddenEntries) SimpleManagementItem(Icons.Filled.Settings, stringRes("settings")) { onChangePopover(null); onNavigate(SimpleDestination.Settings) }
            }
        }
    }
}

/** The panes are see-through; fading their bottom edge keeps them from ending in a hard line on the background. */
internal fun Modifier.fadeBottomEdge(height: Dp = 28.dp): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val fade = height.toPx().coerceAtMost(size.height)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startY = size.height - fade,
                endY = size.height
            ),
            topLeft = Offset(0f, size.height - fade),
            size = androidx.compose.ui.geometry.Size(size.width, fade),
            blendMode = BlendMode.DstIn
        )
    }

/**
 * Springs out of the button that opened it and folds back into it. While it is up, a transparent
 * layer over everything else turns any tap, including the button itself, into a close.
 */
@Composable
internal fun SimplePopoverHost(
    visible: Boolean,
    anchor: Rect?,
    overlayOrigin: Offset,
    anchoredBelow: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (visible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }
        val popoverWidth = 264.dp
        val gap = with(density) { 8.dp.toPx() }
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val popoverWidthPx = with(density) { popoverWidth.toPx() }
        val anchorLocal = anchor?.translate(-overlayOrigin.x, -overlayOrigin.y)
        val fullMaxHeightPx = SimplePopoverPlacementPolicy.maxContentHeightPx(heightPx, gap)
        val maxContentHeightPx = if (!anchoredBelow && anchorLocal != null) {
            (anchorLocal.top - gap * 2f).coerceAtLeast(0f).coerceAtMost(fullMaxHeightPx)
        } else {
            fullMaxHeightPx
        }
        val maxContentHeightDp = with(density) { maxContentHeightPx.toDp() }
        var contentHeightPx by remember { mutableStateOf(with(density) { 360.dp.toPx() }) }
        val placement = if (anchorLocal == null) {
            Modifier.align(Alignment.TopStart)
        } else if (anchoredBelow) {
            val prefersLeft = anchorLocal.center.x > widthPx / 2f
            val rawX = if (prefersLeft) anchorLocal.left - popoverWidthPx - gap else anchorLocal.right + gap
            val x = rawX.coerceIn(gap, (widthPx - popoverWidthPx - gap).coerceAtLeast(gap))
            val y = SimplePopoverPlacementPolicy.computeVerticalOffsetPx(anchorLocal.top, heightPx, contentHeightPx, gap)
            Modifier.align(Alignment.TopStart).offset { IntOffset(x.roundToInt(), y.roundToInt()) }
        } else {
            val x = (anchorLocal.right - popoverWidthPx).coerceIn(gap, (widthPx - popoverWidthPx - gap).coerceAtLeast(gap))
            val rawBottom = heightPx - anchorLocal.top + gap
            val bottomGap = rawBottom.coerceIn(gap, (heightPx - gap).coerceAtLeast(gap))
            Modifier.align(Alignment.BottomStart).offset { IntOffset(x.roundToInt(), -bottomGap.roundToInt()) }
        }
        val origin = if (anchoredBelow) TransformOrigin(0f, 0.12f) else TransformOrigin(0.85f, 1f)
        AnimatedVisibility(
            visible = visible && anchorLocal != null,
            modifier = placement.width(popoverWidth),
            enter = scaleIn(
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                transformOrigin = origin
            ) + fadeIn(tween(120)),
            exit = scaleOut(tween(170), transformOrigin = origin) + fadeOut(tween(140))
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = maxContentHeightDp)
                        .onSizeChanged { contentHeightPx = it.height.toFloat() }
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    content = content
                )
            }
        }
    }
}
