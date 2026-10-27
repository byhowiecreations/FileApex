package com.fileapex.ui.devices

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.fileapex.friends.FriendsHub
import com.fileapex.friends.ui.friendFiles
import com.fileapex.friends.ui.FriendSendSession
import com.fileapex.friends.ui.FriendSendTarget
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.FRIEND_ROW_PREFIX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Pinned friends as device rows, so every theme draws them with the same card, tile, node and menu as a paired device. */
@Composable
fun rememberFriendDeviceRows(): List<DeviceListRow> {
    if (!FriendsHub.available) return emptyList()
    val friends by FriendsHub.friends.collectAsState()
    val pinned by FriendsHub.pinned.collectAsState()
    return remember(friends, pinned) {
        friends.filter { it.friendKey in pinned }.map {
            DeviceListRow(
                deviceId = FRIEND_ROW_PREFIX + it.friendKey, deviceName = it.label, online = true, appVersion = null,
                platform = "friend", friendGroup = it.groupName, friendAutoAccept = it.autoAccept
            )
        }
    }
}

/** Paired devices first; friends join them only after the paired rows have loaded, so a friend never takes a paired device's place. */
fun rowsWithFriendsLast(paired: List<DeviceListRow>, friends: List<DeviceListRow>, pairedLoaded: Boolean): List<DeviceListRow> =
    if (pairedLoaded) paired + friends else paired

/** The friend key behind a friend row id, or null for a paired device. */
internal fun friendKeyOf(deviceId: String): String? =
    if (deviceId.startsWith(FRIEND_ROW_PREFIX)) deviceId.removePrefix(FRIEND_ROW_PREFIX).ifEmpty { null } else null

/** What the row's menu does for a friend: Browse sends a file, Details toggles Auto-accept, Remove unpins, Clipboard is absent. */
class FriendDeviceActions internal constructor(
    private val scope: CoroutineScope,
    private val showLocalFiles: () -> Unit,
    private val startRename: (String, String) -> Unit
) {
    fun isFriend(deviceId: String) = deviceId.startsWith(FRIEND_ROW_PREFIX)
    private fun key(deviceId: String) = deviceId.removePrefix(FRIEND_ROW_PREFIX)

    /** Send: shows this device's files in the app's own file screen, as Local Files does, and asks it to pick for the friend. */
    fun open(deviceId: String): Boolean = isFriend(deviceId).also {
        if (it) {
            val friend = FriendsHub.friends.value.firstOrNull { f -> f.friendKey == key(deviceId) }
            FriendSendSession.begin(FriendSendTarget(key(deviceId), friend?.label.orEmpty()))
            showLocalFiles()
        }
    }

    fun rename(deviceId: String, name: String): Boolean = isFriend(deviceId).also { if (it) startRename(key(deviceId), name) }

    fun details(deviceId: String): Boolean {
        if (!isFriend(deviceId)) return false
        val friend = FriendsHub.friends.value.firstOrNull { it.friendKey == key(deviceId) } ?: return true
        scope.launch { FriendsHub.setAutoAccept(friend.friendKey, !friend.autoAccept) }
        return true
    }

    fun remove(deviceId: String): Boolean = isFriend(deviceId).also { if (it) FriendsHub.setPinned(key(deviceId), false) }

    fun drop(deviceId: String, paths: List<String>): Boolean {
        if (!isFriend(deviceId)) return false
        friendFiles(paths).takeIf { it.isNotEmpty() }?.let { files -> scope.launch { FriendsHub.enqueueSend(key(deviceId), files) } }
        return true
    }
}

@Composable
fun rememberFriendDeviceActions(showLocalFiles: () -> Unit): FriendDeviceActions {
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<Pair<String, String>?>(null) }
    val showLocal by rememberUpdatedState(showLocalFiles)
    renaming?.let { (key, current) ->
        var text by remember(key) { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringRes("friends_rename")) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { Button(onClick = { scope.launch { FriendsHub.rename(key, text) }; renaming = null }) { Text(stringRes("save")) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringRes("cancel")) } }
        )
    }
    return remember(scope) { FriendDeviceActions(scope, { showLocal() }, { key, name -> renaming = key to name }) }
}

/** Set around a menu while it draws a friend row, so the shared menu tiles pick the friend icon and label. */
internal class FriendMenuInfo(val autoAccept: Boolean)

internal val LocalFriendMenu = compositionLocalOf<FriendMenuInfo?> { null }

@Composable
internal fun ProvideFriendMenu(row: DeviceListRow, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFriendMenu provides (if (row.isFriend) FriendMenuInfo(row.friendAutoAccept) else null), content)
}

@Composable
internal fun FreestyleMenuAction.menuIcon(): ImageVector {
    val friend = LocalFriendMenu.current ?: return icon
    return when (this) {
        FreestyleMenuAction.BROWSE -> Icons.Filled.UploadFile
        FreestyleMenuAction.INFO -> if (friend.autoAccept) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank
        FreestyleMenuAction.REMOVE -> Icons.Filled.PushPin
        else -> icon
    }
}

@Composable
internal fun FreestyleMenuAction.menuShortLabel(): String {
    if (LocalFriendMenu.current == null) return shortLabel
    return when (this) {
        FreestyleMenuAction.BROWSE -> stringRes("friends_short_send")
        FreestyleMenuAction.INFO -> stringRes("friends_short_accept")
        FreestyleMenuAction.REMOVE -> stringRes("friends_short_unpin")
        else -> shortLabel
    }
}

@Composable
internal fun FreestyleMenuAction.menuFullLabel(): String {
    if (LocalFriendMenu.current == null) return stringRes(fullLabelRes)
    return when (this) {
        FreestyleMenuAction.BROWSE -> stringRes("friends_send_file")
        FreestyleMenuAction.INFO -> stringRes("groups_header_auto_accept")
        FreestyleMenuAction.REMOVE -> stringRes("friends_unpin")
        else -> stringRes(fullLabelRes)
    }
}

/** The text of a dropdown entry in the list themes: the same entries as a device, with the friend wording for Details and Remove. */
@Composable
internal fun deviceMenuText(row: DeviceListRow, key: String): String {
    if (!row.isFriend) return stringRes(key)
    return when (key) {
        "device_details" -> stringRes("groups_header_auto_accept")
        "remove" -> stringRes("friends_unpin")
        else -> stringRes(key)
    }
}
