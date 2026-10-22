package com.fileapex.platform

import com.fileapex.friends.FriendsHub
import java.io.File

private const val FRIEND_ID_PREFIX = "friend:"

/** Friends as extra rows of the tray list: the tray UIs already list rows and drop files on them, so a friend is a row with a prefixed id. */
fun friendTraySnapshots(): List<DesktopTrayDeviceSnapshot> {
    if (!FriendsHub.available) return emptyList()
    return FriendsHub.friends.value.map {
        DesktopTrayDeviceSnapshot(id = FRIEND_ID_PREFIX + it.friendKey, name = "${it.label} (${it.groupName})", isOnline = true)
    }
}

fun isFriendTrayId(id: String): Boolean = id.startsWith(FRIEND_ID_PREFIX)

/** Queues the files for every friend row in [ids] (folders are skipped) and returns the ids that are real devices. */
suspend fun sendTrayFriends(ids: List<String>, paths: List<String>): List<String> {
    val files = paths.map { File(it) }.filter { it.isFile }
    ids.filter(::isFriendTrayId).forEach { FriendsHub.enqueueSend(it.removePrefix(FRIEND_ID_PREFIX), files) }
    return ids.filterNot(::isFriendTrayId)
}
