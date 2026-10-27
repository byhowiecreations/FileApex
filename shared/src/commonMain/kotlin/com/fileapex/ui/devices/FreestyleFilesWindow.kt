package com.fileapex.ui.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.fileapex.data.settings.LocalThemeIconStyle
import com.fileapex.friends.FriendsHub
import com.fileapex.friends.ui.friendFiles
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.BrowseTarget
import com.fileapex.ui.DeviceEntryIcon
import com.fileapex.ui.FileExplorerScreen
import com.fileapex.ui.deviceIconFillsOrb
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun FreestyleScope.FreestyleFilesWindow() {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
        // Floating Movable & Resizable In-App File Browser Window (No scrim covering canvas)
        if (showFilesWindow) {
            val minWidthDp = 320.dp
            val minHeightDp = 340.dp
            val maxWidthDp = (maxWidth - 32.dp).coerceAtLeast(minWidthDp)
            val maxHeightDp = (maxHeight - 32.dp).coerceAtLeast(minHeightDp)
            val roomWidthPx = (widthPx - marginPx * 2f).coerceAtLeast(1f)
            val roomHeightPx = (heightPx - topMarginPx - bottomMarginPx).coerceAtLeast(1f)
            val fittedWidthPx = fittedPanelPx(with(density) { windowWidthDp.toPx() }, roomWidthPx, with(density) { minWidthDp.toPx() })
            val fittedHeightPx = fittedPanelPx(with(density) { windowHeightDp.toPx() }, roomHeightPx, with(density) { minHeightDp.toPx() })
            val fittedWidthDp = with(density) { fittedWidthPx.toDp() }
            val fittedHeightDp = with(density) { fittedHeightPx.toDp() }

            Surface(
                modifier = Modifier
                    .offset { IntOffset(windowOffsetPx.x.roundToInt(), windowOffsetPx.y.roundToInt()) }
                    .size(width = fittedWidthDp, height = fittedHeightDp)
                    .zIndex(150f)
                    .shadow(24.dp, RoundedCornerShape(18.dp))
                    .border(BorderStroke(1.5.dp, Color(0xFF64B5F6).copy(alpha = 0.85f)), RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xF8081420)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        FreestyleFilesWindowTitleBar(fittedWidthPx, fittedHeightPx)

                        FreestyleFilesWindowBody(this@FreestyleFilesWindow)
                    }

                    // Resize Grip on Bottom-Right Corner
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(36.dp)
                            .pointerInput(density) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val deltaWDp = with(density) { dragAmount.x.toDp() }
                                    val deltaHDp = with(density) { dragAmount.y.toDp() }
                                    windowWidthDp = (windowWidthDp + deltaWDp).coerceIn(minWidthDp, maxWidthDp)
                                    windowHeightDp = (windowHeightDp + deltaHDp).coerceIn(minHeightDp, maxHeightDp)
                                }
                            }
                            .padding(6.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Icon(
                            imageVector = Icons.Filled.OpenWith,
                            contentDescription = "Resize Window",
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

    } } } }
}


@Composable
internal fun FreestyleScope.FreestyleFilesWindowTitleBar(currentWindowWidthPx: Float, currentWindowHeightPx: Float) {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D2235))
                .pointerInput(widthPx, heightPx, currentWindowWidthPx, currentWindowHeightPx) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val nextX = clampPanelOffset(
                            windowOffsetPx.x + dragAmount.x,
                            marginPx,
                            widthPx - marginPx - currentWindowWidthPx
                        )
                        val nextY = clampPanelOffset(
                            windowOffsetPx.y + dragAmount.y,
                            topMarginPx,
                            heightPx - bottomMarginPx - currentWindowHeightPx
                        )
                        windowOffsetPx = Offset(nextX, nextY)
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sendingToFriend == null && (browsingTarget != null || isResolvingBrowse)) {
                    IconButton(
                        onClick = {
                            browsingTarget = null
                            isResolvingBrowse = false
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                } else {
                    Icon(
                        imageVector = Icons.Filled.Folder,
                        contentDescription = null,
                        tint = Color(0xFF64B5F6),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                val title = if (sendingToFriend != null) {
                    "${stringRes("friends_send_file")}: ${sendingToFriend?.name}"
                } else if (isResolvingBrowse) {
                    "Connecting to ${resolvingDeviceName ?: "Device"}..."
                } else if (browsingTarget != null) {
                    if (browsingTarget is BrowseTarget.Local) stringRes("local_files")
                    else browsingTarget?.displayName ?: "Files"
                } else {
                    "File Navigation"
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.OpenWith,
                    contentDescription = "Drag Window",
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        showFilesWindow = false
                        browsingTarget = null
                        isResolvingBrowse = false
                        sendingToFriend = null
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    } } } }
}

@Composable
internal fun ColumnScope.FreestyleFilesWindowBody(scope: FreestyleScope) {
    with(scope) { with(inputs) { with(persisted) { with(geometry) { with(live) {
        if (isResolvingBrowse) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = Color(0xFF64B5F6),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Connecting to ${resolvingDeviceName ?: "device"}...",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Loading remote file system...",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        } else if (browsingTarget != null) {
            FreestyleWindowExplorer(scope)
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                // Category 1: Local Files
                Text(
                    text = "LOCAL FILES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = Color(0xFF64B5F6).copy(alpha = 0.85f)
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            if (thisDeviceTarget != null) {
                                browsingTarget = thisDeviceTarget
                            } else {
                                onOpenLocalFiles?.invoke()
                            }
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0x3364B5F6),
                    border = BorderStroke(1.dp, Color(0x5564B5F6))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringRes("local_files"),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White
                            )
                            Text(
                                text = "Browse storage on this device",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.65f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Category 2: Remote Devices
                Text(
                    text = "REMOTE DEVICES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = Color(0xFF64B5F6).copy(alpha = 0.85f)
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))

                val remoteRows = deviceRows.filter { browsesRemoteFolder(it.deviceId) }
                if (remoteRows.isEmpty()) {
                    Text(
                        text = "No paired remote devices found",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        remoteRows.forEach { row ->
                            val statusColor = if (row.online) Color(0xFF00E676) else Color(0xFFFFB300)
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        isResolvingBrowse = true
                                        resolvingDeviceName = row.deviceName
                                        if (onResolveBrowseTarget != null) {
                                            onResolveBrowseTarget(row.deviceId) { target ->
                                                browsingTarget = target
                                                isResolvingBrowse = false
                                            }
                                        } else {
                                            isResolvingBrowse = false
                                            onOpenDevice(row.deviceId)
                                        }
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0x221E3A5F),
                                border = BorderStroke(1.dp, Color(0x4464B5F6))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val iconStyle = LocalThemeIconStyle.current
                                    DeviceEntryIcon(
                                        row = row,
                                        modifier = if (deviceIconFillsOrb(iconStyle)) {
                                            Modifier.size(36.dp)
                                        } else {
                                            Modifier.size(24.dp)
                                        },
                                        tint = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = row.deviceName,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (row.online) "Online" else "Offline",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = statusColor
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Filled.FolderOpen,
                                        contentDescription = null,
                                        tint = Color(0xFF64B5F6),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    } } } } }
}

/** The file screen inside the window: a remote or local folder to browse, or this device's files to pick for a friend. */
@Composable
private fun ColumnScope.FreestyleWindowExplorer(scope: FreestyleScope) {
    with(scope) { with(live) {
            val friend = sendingToFriend
            val sendScope = rememberCoroutineScope()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                FileExplorerScreen(
                    target = browsingTarget!!,
                    onBack = { browsingTarget = null; sendingToFriend = null; showFilesWindow = false },
                    embeddedInCompactShell = true,
                    titleOverride = if (browsingTarget is BrowseTarget.Local) stringRes("local_files") else null,
                    hostSplit = friend == null,
                    viewModelKey = if (friend == null) browsingTarget!!.deviceId else "friend-file-pick",
                    onPickFiles = friend?.let { target ->
                        { paths ->
                            friendFiles(paths).takeIf { it.isNotEmpty() }?.let { files ->
                                sendScope.launch { FriendsHub.enqueueSend(target.key, files) }
                            }
                            sendingToFriend = null
                            browsingTarget = null
                            showFilesWindow = false
                        }
                    }
                )
            }
    } }
}
