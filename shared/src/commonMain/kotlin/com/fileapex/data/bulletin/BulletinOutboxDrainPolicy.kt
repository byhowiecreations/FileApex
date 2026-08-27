package com.fileapex.data.bulletin

object BulletinOutboxDrainPolicy {
    fun shouldAttemptPeer(
        supportsBulletinSync: Boolean,
        host: String,
        port: Int,
        isOnline: Boolean
    ): Boolean {
        if (!supportsBulletinSync) return false
        if (host.isBlank() || port <= 0) return false
        return isOnline
    }
}
