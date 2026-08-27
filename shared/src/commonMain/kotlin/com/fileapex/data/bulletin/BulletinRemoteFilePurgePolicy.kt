package com.fileapex.data.bulletin

object BulletinRemoteFilePurgePolicy {
    fun shouldScrubLocalCopy(
        isAndroid: Boolean,
        selfDeviceId: String,
        originNode: String,
        messageOriginDeviceId: String
    ): Boolean {
        if (!isAndroid) return false
        val self = selfDeviceId.trim()
        if (self.isEmpty()) return false
        val origin = originNode.trim().ifBlank { messageOriginDeviceId.trim() }
        if (origin.isEmpty()) return false
        return origin != self
    }
}
