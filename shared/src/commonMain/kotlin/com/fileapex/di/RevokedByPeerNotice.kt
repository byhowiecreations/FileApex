package com.fileapex.di

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.BriefToast
import com.fileapex.util.TimeUtils
import java.util.concurrent.ConcurrentHashMap

/** Tells the user once per peer (per [REPEAT_AFTER_MS]) that a peer's gate refuses this device. */
internal object RevokedByPeerNotice {
    private const val REPEAT_AFTER_MS = 10L * 60L * 1000L
    private val lastShown = ConcurrentHashMap<String, Long>()

    fun onRevoked(host: String, roster: List<PairedDeviceEntity>) {
        val key = host.trim()
        if (key.isEmpty()) return
        val now = TimeUtils.now()
        val previous = lastShown[key]
        if (previous != null && now - previous < REPEAT_AFTER_MS) return
        lastShown[key] = now
        val name = roster.firstOrNull { it.lastKnownIp.trim() == key }?.deviceName?.takeIf { it.isNotBlank() } ?: key
        BriefToast.show(AppI18n.t("peer_revoked_this_device", name))
    }
}
