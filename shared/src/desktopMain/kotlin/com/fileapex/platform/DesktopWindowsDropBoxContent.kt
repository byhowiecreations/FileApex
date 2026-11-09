package com.fileapex.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.i18n.AppI18n
import com.fileapex.ui.dnd.deviceFileDropTarget

/** The Drop Files panel: destination, pin, a drop zone that turns into the list of staged files, and the cancel and send buttons. */
@Composable
internal fun DropBoxContent(
    stagedEntries: List<DesktopStagedEntry>,
    isSending: Boolean,
    isPinned: Boolean,
    isDragOver: Boolean,
    destinationText: String,
    onTogglePin: () -> Unit,
    onDragOverChange: (Boolean) -> Unit,
    onFilesDropped: (List<String>) -> Unit,
    onRemovePath: (String) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit
) = RedrawOnLanguageChange {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DesktopTrayStyle.BackgroundTop, DesktopTrayStyle.Background)))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            DropBoxHeader(destinationText, isPinned, onTogglePin)
            Spacer(modifier = Modifier.height(12.dp))

            val zoneColor = if (isDragOver) DesktopTrayStyle.Online else DesktopTrayStyle.Muted.copy(alpha = 0.55f)
            Tip(AppI18n.t("dropbox_zone_tip"), modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (isDragOver) DesktopTrayStyle.Online.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.04f))
                        .drawBehind {
                            drawRoundRect(
                                color = zoneColor,
                                cornerRadius = CornerRadius(18.dp.toPx()),
                                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f)))
                            )
                        }
                        .deviceFileDropTarget(onHoverChange = onDragOverChange, onFilesDropped = onFilesDropped)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (stagedEntries.isEmpty()) {
                        EmptyDropZone(isDragOver)
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = AppI18n.plural("n_files_ready", stagedEntries.size, stagedEntries.size.toString()),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                            )
                            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(stagedEntries, key = { it.path }) { entry -> StagedEntryRow(entry, onRemovePath) }
                            }
                            if (isSending) {
                                Spacer(modifier = Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = DesktopTrayStyle.Online,
                                    trackColor = Color.White.copy(alpha = 0.1f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Tip(AppI18n.t("dropbox_cancel_tip")) {
                    Box(
                        modifier = Modifier
                            .width(110.dp)
                            .height(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, DesktopTrayStyle.Border, RoundedCornerShape(12.dp))
                            .clickable(onClick = onCancel),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = AppI18n.t("cancel"), color = Color.White, fontSize = 13.sp)
                    }
                }
                if (stagedEntries.isNotEmpty()) {
                    Box(modifier = Modifier.weight(1f)) {
                        Tip(AppI18n.t("dropbox_send_tip")) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp)
                                    .alpha(if (isSending) 0.6f else 1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DesktopTrayStyle.Accent)
                                    .clickable(enabled = !isSending, onClick = onSend),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isSending) AppI18n.t("sending_short") else AppI18n.t("send"),
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DropBoxHeader(destinationText: String, isPinned: Boolean, onTogglePin: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            Tip(AppI18n.t("dropbox_destination_tip")) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(DesktopTrayStyle.Online.copy(alpha = 0.14f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = DesktopTrayStyle.Online, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = destinationText,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        val pinTip = if (isPinned) AppI18n.t("unpin_drop_box") else AppI18n.t("pin_drop_box")
        Tip(pinTip) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isPinned) DesktopTrayStyle.Accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.06f))
                    .clickable(onClick = onTogglePin),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = pinTip,
                    tint = if (isPinned) Color.White else DesktopTrayStyle.Muted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyDropZone(isDragOver: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (isDragOver) DesktopTrayStyle.Online.copy(alpha = 0.25f) else DesktopTrayStyle.Online.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.FileDownload, contentDescription = null, tint = DesktopTrayStyle.Online, modifier = Modifier.size(34.dp))
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = AppI18n.t("drag_drop_files_here"),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StagedEntryRow(entry: DesktopStagedEntry, onRemovePath: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (entry.isDirectory) DesktopTrayStyle.Folder.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                tint = if (entry.isDirectory) DesktopTrayStyle.Folder else DesktopTrayStyle.Muted,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = entry.name,
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Tip(AppI18n.t("dropbox_remove_tip")) {
            Box(
                modifier = Modifier.size(26.dp).clip(CircleShape).clickable { onRemovePath(entry.path) },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, contentDescription = AppI18n.t("dropbox_remove_tip"), tint = DesktopTrayStyle.Muted, modifier = Modifier.size(15.dp))
            }
        }
    }
}
