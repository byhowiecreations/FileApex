package com.fileapex.platform

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.i18n.AppI18n
import com.fileapex.presentation.DeviceIconKind
import com.fileapex.ui.deviceIconVector

internal const val TRAY_COLUMN_WIDTH_DP = 300
internal const val TRAY_ROW_HEIGHT_DP = 52
internal const val TRAY_CHROME_HEIGHT_DP = 150
internal const val TRAY_COLUMN_GAP_DP = 8
internal const val TRAY_PADDING_DP = 12

/** Total width of the flyout for [columns] columns of devices. */
internal fun trayMenuWidthDp(columns: Int): Int =
    TRAY_COLUMN_WIDTH_DP * columns + TRAY_COLUMN_GAP_DP * (columns - 1) + TRAY_PADDING_DP * 2

/**
 * The Windows tray flyout: app header with labelled Open and Quit, paired devices as tiles (type icon, clipboard button,
 * greyed when offline), Ctrl-click to pick several, a Check Batteries button, and a note about multi-select at the bottom.
 * Devices flow into extra columns when the list is taller than the screen, so the flyout never scrolls.
 */
@Composable
fun DesktopWindowsTrayMenu(
    devices: List<DesktopTrayDeviceSnapshot>,
    selectedDeviceIds: Set<String>,
    columns: Int,
    checkingBatteries: Boolean,
    onDeviceClick: (DesktopTrayDeviceSnapshot) -> Unit,
    onDeviceCtrlClick: (DesktopTrayDeviceSnapshot) -> Unit,
    onSendClipboard: (DesktopTrayDeviceSnapshot) -> Unit,
    onBrowse: (DesktopTrayDeviceSnapshot) -> Unit,
    onPostNote: (String) -> Unit,
    onCheckBatteries: () -> Unit,
    onSendMulti: () -> Unit,
    onLaunchApp: () -> Unit,
    onQuitApp: () -> Unit,
    modifier: Modifier = Modifier,
    startWithNoteOpen: Boolean = false
) = RedrawOnLanguageChange {
    val appIcon = remember { loadTrayAppIcon() }
    var noteOpen by remember { mutableStateOf(startWithNoteOpen) }
    Box(
        modifier = modifier
            .width(trayMenuWidthDp(columns).dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(DesktopTrayStyle.BackgroundTop, DesktopTrayStyle.Background)))
            .border(1.dp, DesktopTrayStyle.Border, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(TRAY_PADDING_DP.dp)) {
            TrayHeader(appIcon, onPostToggle = { noteOpen = !noteOpen }, onLaunchApp = onLaunchApp, onQuitApp = onQuitApp)
            if (noteOpen) {
                Spacer(modifier = Modifier.height(10.dp))
                NoteComposer(onPost = { text ->
                    noteOpen = false
                    onPostNote(text)
                })
            }
            Spacer(modifier = Modifier.height(10.dp))

            if (devices.isEmpty()) {
                Text(
                    text = AppI18n.t("no_paired_devices"),
                    color = DesktopTrayStyle.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                val perColumn = (devices.size + columns - 1) / columns
                Row(horizontalArrangement = Arrangement.spacedBy(TRAY_COLUMN_GAP_DP.dp)) {
                    devices.chunked(perColumn).forEach { columnDevices ->
                        Column(modifier = Modifier.width(TRAY_COLUMN_WIDTH_DP.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            columnDevices.forEach { device ->
                                TrayDeviceRow(
                                    device = device,
                                    isSelected = device.id in selectedDeviceIds,
                                    onClick = { isCtrl -> if (isCtrl) onDeviceCtrlClick(device) else onDeviceClick(device) },
                                    onSendClipboard = { onSendClipboard(device) },
                                    onBrowse = { onBrowse(device) }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BatteryButton(checkingBatteries, onCheckBatteries)
                if (selectedDeviceIds.size > 1) {
                    Box(modifier = Modifier.weight(1f)) {
                        Tip(AppI18n.t("tray_send_multi_tip")) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DesktopTrayStyle.Accent)
                                    .clickable(onClick = onSendMulti),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = AppI18n.t("send_to_n_devices", selectedDeviceIds.size),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Tip(AppI18n.t("tray_multi_select_tip")) {
                    Text(
                        text = AppI18n.t("ctrl_click_multi_select"),
                        color = DesktopTrayStyle.Muted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun TrayHeader(appIcon: ImageBitmap?, onPostToggle: () -> Unit, onLaunchApp: () -> Unit, onQuitApp: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Tip(AppI18n.t("tray_app_tip")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(text = "FileApex", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        HeaderAction(Icons.Default.PushPin, AppI18n.t("tray_post"), AppI18n.t("tray_post_tip"), onPostToggle)
        HeaderAction(Icons.Default.PowerSettingsNew, AppI18n.t("tray_quit"), AppI18n.t("quit_application"), onQuitApp)
        HeaderAction(Icons.AutoMirrored.Filled.OpenInNew, AppI18n.t("tray_open"), AppI18n.t("launch_full_app"), onLaunchApp)
    }
}

@Composable
private fun HeaderAction(icon: ImageVector, label: String, tip: String, onClick: () -> Unit) {
    Tip(tip) {
        Column(
            modifier = Modifier.width(48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = icon, contentDescription = tip, tint = Color.White, modifier = Modifier.size(18.dp))
            Text(text = label, color = DesktopTrayStyle.Muted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun NoteComposer(onPost: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, DesktopTrayStyle.Border, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                            onPost(text)
                            true
                        } else {
                            false
                        }
                    }
            )
            if (text.isEmpty()) {
                Text(
                    text = AppI18n.t("tray_post_hint"),
                    color = DesktopTrayStyle.Muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DesktopTrayStyle.Accent)
                .clickable { onPost(text) }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = AppI18n.t("tray_post"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BatteryButton(checking: Boolean, onClick: () -> Unit) = Tip(AppI18n.t("tray_check_batteries_tip")) {
    Row(
        modifier = Modifier
            .alpha(if (checking) 0.6f else 1f)
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, DesktopTrayStyle.Border, RoundedCornerShape(8.dp))
            .clickable(enabled = !checking, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.BatteryFull, contentDescription = null, tint = DesktopTrayStyle.Muted, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = AppI18n.t("check_batteries"), color = Color.White, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun TrayDeviceRow(
    device: DesktopTrayDeviceSnapshot,
    isSelected: Boolean,
    onClick: (isCtrlPressed: Boolean) -> Unit,
    onSendClipboard: () -> Unit,
    onBrowse: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFriend = isFriendTrayId(device.id)

    val rowBg = when {
        isSelected -> DesktopTrayStyle.Accent.copy(alpha = 0.28f)
        isHovered -> Color.White.copy(alpha = 0.07f)
        else -> DesktopTrayStyle.Card.copy(alpha = 0.55f)
    }
    val rowBorder = if (isSelected) DesktopTrayStyle.Accent else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TRAY_ROW_HEIGHT_DP.dp - 4.dp)
            .alpha(if (device.isOnline) 1f else 0.55f)
            .clip(RoundedCornerShape(10.dp))
            .background(rowBg)
            .border(1.dp, rowBorder, RoundedCornerShape(10.dp))
            .hoverable(interactionSource)
            .pointerInput(device.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Release) {
                            val change = event.changes.firstOrNull()
                            if (change != null && !change.isConsumed) {
                                change.consume()
                                onClick(event.keyboardModifiers.isCtrlPressed)
                            }
                        }
                    }
                }
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Tip(AppI18n.t(device.iconKey)) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (device.isOnline) DesktopTrayStyle.Online.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = trayIconVector(device.iconKind),
                    contentDescription = null,
                    tint = if (device.isOnline) DesktopTrayStyle.Online else DesktopTrayStyle.Muted,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Tip(AppI18n.t("tray_row_tip", device.name)) {
                Text(
                    text = device.name,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tip(AppI18n.t(if (device.isOnline) "tray_online_tip" else "tray_offline_tip")) {
                    Text(
                        text = if (device.isOnline) AppI18n.t("online") else AppI18n.t("offline"),
                        color = if (device.isOnline) DesktopTrayStyle.Muted else DesktopTrayStyle.Offline,
                        fontSize = 11.sp
                    )
                }
                if (device.batteryText.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Tip(AppI18n.t("tray_battery_tip")) { BatteryPill(device.batteryText, device.batteryTone) }
                }
            }
        }
        if (!isFriend) {
            RowIconButton(
                icon = Icons.Default.ContentPaste,
                description = AppI18n.t(if (device.isOnline) "tray_send_clipboard_tip" else "tray_send_clipboard_offline_tip"),
                enabled = device.isOnline,
                onClick = onSendClipboard
            )
            Spacer(modifier = Modifier.width(4.dp))
            RowIconButton(
                icon = Icons.Default.Folder,
                description = AppI18n.t(if (device.isOnline) "tray_browse_tip" else "tray_browse_offline_tip"),
                enabled = device.isOnline,
                onClick = onBrowse
            )
        }
    }
}

@Composable
private fun RowIconButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Tip(description) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .alpha(if (enabled) 1f else 0.35f)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, DesktopTrayStyle.Border, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun BatteryPill(text: String, tone: String) {
    val color = when (tone) {
        TrayBatteryTone.Good.name -> DesktopTrayStyle.Online
        TrayBatteryTone.Low.name -> DesktopTrayStyle.Warning
        else -> DesktopTrayStyle.Offline
    }
    Box(
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(color.copy(alpha = 0.2f)).padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(text = text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private fun trayIconVector(kind: String): ImageVector =
    if (kind == FRIEND_ICON_KIND) {
        Icons.Default.Person
    } else {
        deviceIconVector(DeviceIconKind.entries.firstOrNull { it.name == kind } ?: DeviceIconKind.AndroidPhone)
    }

private fun loadTrayAppIcon(): ImageBitmap? =
    (DesktopAppIcon.loadTrayImage() as? java.awt.image.BufferedImage)?.toComposeImageBitmap()

/** A small dark hint that appears after the pointer rests on [content]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Tip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Box(
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DesktopTrayStyle.Tooltip)
                    .border(1.dp, DesktopTrayStyle.Border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(text = text, color = Color.White, fontSize = 12.sp)
            }
        },
        modifier = modifier,
        delayMillis = 450,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
        content = content
    )
}
