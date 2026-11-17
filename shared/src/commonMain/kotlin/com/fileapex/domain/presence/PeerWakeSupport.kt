package com.fileapex.domain.presence

/**
 * Whether "Tap to wake" tells the truth for a peer: only an Android phone a cloud message can reach can be started
 * from outside. A computer, a Docker node or a device of unknown kind cannot, and a build without Google (F-Droid) can
 * neither send a wake nor be sent one, so none of those show it.
 */
object PeerWakeSupport {
    fun canWake(os: String, platform: String, cloudReachable: Boolean, thisBuildIsFdroid: Boolean = false): Boolean {
        if (thisBuildIsFdroid || !cloudReachable) return false
        val slug = os.trim().ifBlank { platform.trim() }.lowercase()
        return slug == "android"
    }
}
