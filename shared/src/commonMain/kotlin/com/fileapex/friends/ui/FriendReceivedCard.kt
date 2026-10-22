package com.fileapex.friends.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.fileapex.friends.FriendReceivedFile
import com.fileapex.friends.FriendsHub
import com.fileapex.i18n.stringRes
import com.fileapex.platform.openLocalFile
import com.fileapex.platform.revealInFolder
import kotlinx.coroutines.delay

private const val CARD_SHOWN_MS = 10_000L

/** Small card at the bottom of the window when a friend's file has arrived; View opens it, Open location shows its folder. */
@Composable
fun FriendReceivedHost() {
    var file by remember { mutableStateOf<FriendReceivedFile?>(null) }
    LaunchedEffect(Unit) { FriendsHub.received.collect { file = it } }
    val shown = file ?: return
    LaunchedEffect(shown) { delay(CARD_SHOWN_MS); if (file === shown) file = null }
    Popup(alignment = Alignment.BottomCenter, properties = PopupProperties(focusable = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.padding(16.dp).widthIn(max = 420.dp).fillMaxWidth()
        ) {
            Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
                Text(stringRes("friends_received_title", shown.friendLabel), style = MaterialTheme.typography.titleSmall)
                Text(shown.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { revealInFolder(shown.path); file = null }) { Text(stringRes("friends_open_location")) }
                    TextButton(onClick = { openLocalFile(shown.path); file = null }) { Text(stringRes("friends_view")) }
                }
            }
        }
    }
}
