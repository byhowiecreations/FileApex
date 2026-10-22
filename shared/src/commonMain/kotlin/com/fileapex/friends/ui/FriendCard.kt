package com.fileapex.friends.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.friends.data.FriendLinkEntity
import com.fileapex.friends.transfer.FriendTransfer
import com.fileapex.friends.transfer.FriendTransferStatus
import com.fileapex.i18n.stringRes
import com.fileapex.ui.dnd.deviceFileDropTarget

/** A person has no device photo (friends share no device details), so the card shows their initial on a colour taken from the group. */
@Composable
fun FriendAvatar(label: String, group: String, size: Dp, transfers: List<FriendTransfer> = emptyList(), modifier: Modifier = Modifier) {
    val hue = (group.lowercase().hashCode() and 0x7fffffff) % 360
    val base = Color.hsv(hue.toFloat(), 0.45f, 0.62f)
    val active = transfers.firstOrNull { !it.status.finished && it.status != FriendTransferStatus.QUEUED }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size - 8.dp).clip(CircleShape).background(base), contentAlignment = Alignment.Center) {
            Text(label.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        }
        if (active != null) {
            if (active.status == FriendTransferStatus.ACTIVE && active.totalBytes > 0) CircularProgressIndicator(progress = { active.fraction }, modifier = Modifier.size(size), strokeWidth = 3.dp)
            else CircularProgressIndicator(modifier = Modifier.size(size), strokeWidth = 3.dp)
        }
    }
}

/** A friend as a card like a device card: avatar with a progress ring, name, group, its transfers and a menu. Tap sends a file. */
@Composable
internal fun FriendCard(
    friend: FriendLinkEntity,
    transfers: List<FriendTransfer>,
    pinned: Boolean,
    onSend: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onAutoAccept: (Boolean) -> Unit,
    onPin: (Boolean) -> Unit,
    onDroppedOnHome: () -> Unit = {},
    onFilesDropped: (List<java.io.File>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var menu by remember { mutableStateOf(false) }
    var cardOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var outside by remember { mutableStateOf(false) }
    var dropHover by remember { mutableStateOf(false) }
    // Long-press and drag the card out of the panel and let go: that pins the friend to the home Devices page.
    fun outsidePanel(pointer: androidx.compose.ui.geometry.Offset): Boolean = FriendsUi.panelBounds?.let { !it.contains(pointer) } ?: false
    Surface(
        onClick = onSend,
        modifier = modifier
            .onGloballyPositioned { cardOrigin = it.positionInRoot() }
            .deviceFileDropTarget(onHoverChange = { dropHover = it }, onFilesDropped = { paths -> friendFiles(paths).takeIf { it.isNotEmpty() }?.let(onFilesDropped) })
            .pointerInput(friend.friendKey) {
                var pointer = androidx.compose.ui.geometry.Offset.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart = { pointer = cardOrigin + it; outside = false },
                    onDrag = { change, drag -> change.consume(); pointer += drag; outside = outsidePanel(pointer) },
                    onDragEnd = { val drop = outsidePanel(pointer); outside = false; if (drop) onDroppedOnHome() },
                    onDragCancel = { outside = false }
                )
            },
        shape = RoundedCornerShape(20.dp),
        color = if (dropHover) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (outside) 0.3f else 0.72f),
        border = BorderStroke(if (outside) 2.dp else 1.dp, if (outside) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FriendAvatar(friend.label, friend.groupName, 56.dp, transfers)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(friend.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(friend.groupName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (pinned) Icon(Icons.Filled.PushPin, contentDescription = stringRes("friends_pinned"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringRes("friends_menu")) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringRes("friends_send_file")) }, onClick = { menu = false; onSend() })
                        DropdownMenuItem(text = { Text(stringRes("friends_rename")) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(
                            text = { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = friend.autoAccept, onCheckedChange = null); Text(stringRes("groups_header_auto_accept")) } },
                            onClick = { onAutoAccept(!friend.autoAccept) }
                        )
                        DropdownMenuItem(text = { Text(stringRes(if (pinned) "friends_unpin" else "friends_pin_device")) }, onClick = { menu = false; onPin(!pinned) })
                        DropdownMenuItem(text = { Text(stringRes("friends_remove"), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onRemove() })
                    }
                }
            }
            transfers.forEach { FriendTransferRow(it) }
        }
    }
}

/** A friend takes single files only; dropped folders are skipped. */
fun friendFiles(paths: List<String>): List<java.io.File> = paths.map { java.io.File(it) }.filter { it.isFile }

/** The person's initial on the group colour, sized by whatever the theme gives it, with a ring while a transfer runs. Used where a device icon would be. */
@Composable
fun FriendGlyph(label: String, group: String, friendKey: String, modifier: Modifier = Modifier) {
    val hue = (group.lowercase().hashCode() and 0x7fffffff) % 360
    val transfers by com.fileapex.friends.FriendsHub.transfers.collectAsState()
    val active = transfers.firstOrNull { it.friendKey == friendKey && !it.status.finished && it.status != FriendTransferStatus.QUEUED }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.clip(CircleShape).background(Color.hsv(hue.toFloat(), 0.45f, 0.62f)), contentAlignment = Alignment.Center) {
        Text(label.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (minOf(maxWidth, maxHeight).value * 0.45f).sp)
        if (active != null) {
            if (active.status == FriendTransferStatus.ACTIVE && active.totalBytes > 0) CircularProgressIndicator(progress = { active.fraction }, modifier = Modifier.fillMaxSize(), strokeWidth = 3.dp)
            else CircularProgressIndicator(modifier = Modifier.fillMaxSize(), strokeWidth = 3.dp)
        }
    }
}
