package com.fileapex.friends.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.friends.FriendsHub
import com.fileapex.friends.transfer.FriendTransfer
import com.fileapex.friends.transfer.FriendTransferDirection
import com.fileapex.friends.transfer.FriendTransferStatus
import com.fileapex.i18n.stringRes

/** One file moving to or from a friend: name, progress bar, bytes and speed or the state, and Cancel while it is unfinished. */
@Composable
internal fun FriendTransferRow(transfer: FriendTransfer) {
    val moving = transfer.status == FriendTransferStatus.ACTIVE && transfer.totalBytes > 0
    val working = !transfer.status.finished && transfer.status != FriendTransferStatus.QUEUED
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(transfer.fileName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (!transfer.status.finished) TextButton(onClick = { FriendsHub.cancel(transfer.id) }) { Text(stringRes("friends_cancel")) }
        }
        if (working) {
            if (moving) LinearProgressIndicator(progress = { transfer.fraction }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(detail(transfer, moving), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun detail(t: FriendTransfer, moving: Boolean): String {
    if (moving) {
        val bytes = stringRes("friends_progress", formatSize(t.doneBytes), formatSize(t.totalBytes))
        return if (t.speedBps > 0) bytes + " · " + stringRes("friends_speed", formatSize(t.speedBps)) else bytes
    }
    val send = t.direction == FriendTransferDirection.SEND
    return stringRes(
        when (t.status) {
            FriendTransferStatus.QUEUED -> "friends_status_queued"
            FriendTransferStatus.PREPARING -> "friends_status_preparing"
            FriendTransferStatus.CONNECTING -> "friends_status_connecting"
            FriendTransferStatus.WAITING -> "friends_status_waiting"
            FriendTransferStatus.VERIFYING -> "friends_status_verifying"
            FriendTransferStatus.ACTIVE -> if (send) "friends_status_sending" else "friends_status_receiving"
            FriendTransferStatus.DONE -> if (send) "friends_status_sent" else "friends_status_received"
            FriendTransferStatus.REFUSED -> when (t.note) { "size" -> "friends_refused_size"; "space" -> "friends_refused_space"; else -> "friends_status_refused" }
            FriendTransferStatus.FAILED -> "friends_status_failed"
            FriendTransferStatus.CANCELED -> "friends_status_canceled"
        }
    )
}
