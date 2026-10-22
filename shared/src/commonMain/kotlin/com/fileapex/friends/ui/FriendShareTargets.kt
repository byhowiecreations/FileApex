package com.fileapex.friends.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fileapex.friends.data.FriendLinkEntity
import com.fileapex.friends.FriendsHub
import com.fileapex.i18n.stringRes
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The friends as destinations in the Android share screen, under the user's own devices. */
fun LazyListScope.friendShareItems(friends: List<FriendLinkEntity>, selected: Set<String>, onToggle: (String) -> Unit) {
    items(items = friends, key = { "friend:" + it.friendKey }) { friend ->
        val checked = friend.friendKey in selected
        Row(
            Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(friend.friendKey) }).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            FriendAvatar(friend.label, friend.groupName, 40.dp, modifier = Modifier.padding(start = 8.dp))
            Column(Modifier.padding(start = 8.dp)) {
                Text(friend.label, style = MaterialTheme.typography.bodyLarge)
                Text(stringRes("friends_badge", friend.groupName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Which friends are ticked in the share screen. */
class FriendShareSelection(val scope: CoroutineScope) {
    var selected by mutableStateOf(FriendsHub.takePreselectedShare()?.let { setOf(it) } ?: emptySet())
    var friends by mutableStateOf(emptyList<FriendLinkEntity>())

    fun toggle(key: String) { selected = if (key in selected) selected - key else selected + key }
}

@Composable
fun rememberFriendShare(): FriendShareSelection {
    val scope = rememberCoroutineScope()
    val holder = remember { FriendShareSelection(scope) }
    holder.friends = FriendsHub.friends.collectAsState().value
    return holder
}

/** The share screen's Send button: ticked friends get private copies of the files queued, ticked devices go through the usual send. */
@Composable
fun ShareSendButton(
    ready: Boolean,
    devicesSelected: Boolean,
    share: FriendShareSelection,
    files: () -> List<File>,
    sendToDevices: () -> Unit,
    finishWithoutDevices: () -> Unit
) {
    Button(
        onClick = {
            if (share.selected.isEmpty()) sendToDevices() else share.scope.launch {
                FriendsHub.enqueueShared(share.selected, files())
                if (devicesSelected) sendToDevices() else finishWithoutDevices()
            }
        },
        enabled = ready && (devicesSelected || share.selected.isNotEmpty()),
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringRes("send")) }
}
