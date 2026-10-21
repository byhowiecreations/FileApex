package com.fileapex.domain.clipboard

import com.fileapex.data.settings.AppSettings

/**
 * Picks the default clipboard target for a first-time setup and stores it as the specific target.
 * Returns the chosen device id, or null when no default applies or the user already chose a mode.
 */
internal fun AppSettings.applyAutoDefaultClipboardTarget(
    selfIsAndroid: Boolean,
    peers: List<ClipboardSharePolicy.PeerRef>
): String? {
    val defaultTargetId = ClipboardSharePolicy.resolveAutoDefaultTargetId(selfIsAndroid, peers) ?: return null
    val mode = clipboardShareMode.value
    if (mode != ClipboardShareMode.UNSET && mode != ClipboardShareMode.SPECIFIC) return null
    setClipboardShareMode(ClipboardShareMode.SPECIFIC)
    setClipboardTargetDeviceIds(setOf(defaultTargetId))
    return defaultTargetId
}
