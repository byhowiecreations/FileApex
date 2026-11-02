package com.fileapex.friends.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fileapex.friends.PendingFriendOffer
import com.fileapex.friends.transfer.FriendTransfer
import com.fileapex.friends.FriendsHub
import com.fileapex.i18n.stringRes
import kotlinx.coroutines.flow.MutableStateFlow

internal object FriendsQueueUi {
    val expanded = MutableStateFlow(false)
}

@Composable
internal fun FriendQueueButton() {
    val transfers by FriendsHub.transfers.collectAsState()
    val offers by FriendsHub.pendingOffers.collectAsState()
    val counts = friendQueueCounts(transfers, offers)
    IconButton(onClick = { FriendsQueueUi.expanded.value = true }, enabled = FriendsHub.available) {
        BadgedBox(badge = { if (counts.total > 0) Badge { Text(counts.total.toString()) } }) {
            Icon(Icons.Filled.Queue, contentDescription = stringRes("friends_queue_open"))
        }
    }
}

@Composable
fun FriendTransfersPanel() {
    val transfers by FriendsHub.transfers.collectAsState()
    val offers by FriendsHub.pendingOffers.collectAsState()
    val expanded by FriendsQueueUi.expanded.collectAsState()
    if (expanded) {
        Dialog(onDismissRequest = { FriendsQueueUi.expanded.value = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            FriendsQueueContents(transfers, offers) { FriendsQueueUi.expanded.value = false }
        }
    }
}

@Composable
internal fun FriendsQueueContents(transfers: List<FriendTransfer>, offers: List<PendingFriendOffer>, onClose: () -> Unit) {
    val counts = friendQueueCounts(transfers, offers)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(16.dp).widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 560.dp)
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringRes("friends_queue_title"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClose) { Text(stringRes("close")) }
            }
            if (!counts.visible) Text(stringRes("friends_queue_empty"))
            offers.distinctBy { it.id }.forEach { offer ->
                Text(stringRes("friends_offer_title", offer.friendLabel), style = MaterialTheme.typography.titleSmall)
                Text(offer.fileName, style = MaterialTheme.typography.bodyMedium)
                Text(formatSize(offer.sizeBytes), style = MaterialTheme.typography.bodySmall)
                Text(stringRes("friends_offer_note"), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { FriendsHub.decide(offer.id, true) }) { Text(stringRes("friends_accept")) }
                    TextButton(onClick = { FriendsHub.decide(offer.id, false) }) { Text(stringRes("friends_decline")) }
                }
            }
            transfers.forEach { transfer ->
                Text(transfer.friendLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                FriendTransferRow(transfer)
            }
        }
    }
}
