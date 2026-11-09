package com.fileapex.platform

import com.fileapex.friends.FriendsHub

private const val FRIEND_ID_PREFIX = "friend:"

/** Friends as extra rows of the tray list: the tray UIs already list rows and drop files on them, so a friend is a row with a prefixed id. */
fun friendTraySnapshots(): List<DesktopTrayDeviceSnapshot> {
    if (!FriendsHub.available) return emptyList()
    return FriendsHub.friends.value.map {
        DesktopTrayDeviceSnapshot(id = FRIEND_ID_PREFIX + it.friendKey, name = "${it.label} (${it.groupName})", isOnline = true, iconKind = FRIEND_ICON_KIND, iconKey = "tray_icon_friend")
    }
}

fun isFriendTrayId(id: String): Boolean = id.startsWith(FRIEND_ID_PREFIX)

/** Queues the paths through Friends intake and returns the ids that are real devices. */
suspend fun sendTrayFriends(ids: List<String>, paths: List<String>): List<String> {
    ids.filter(::isFriendTrayId).forEach { FriendsHub.sendPaths(it.removePrefix(FRIEND_ID_PREFIX), paths) }
    return ids.filterNot(::isFriendTrayId)
}
