package com.fileapex.friends

data class ActiveInvite(val uri: String, val pin: String, val expiresAtMs: Long, val group: String)
/** A friend as the system share sheet lists them. */
data class FriendShareTarget(val key: String, val label: String, val group: String)

/** A file a friend sent that is now complete in the friend's folder. */
data class FriendReceivedFile(val friendLabel: String, val fileName: String, val path: String)

data class PendingFriendOffer(val id: String, val friendLabel: String, val fileName: String, val sizeBytes: Long, val offeredAtMs: Long = 0L, val friendKey: String = "", val fileCount: Int = 1)
