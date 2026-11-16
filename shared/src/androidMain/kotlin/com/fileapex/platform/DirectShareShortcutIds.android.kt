package com.fileapex.platform

import android.content.Intent
import android.net.Uri

/** The ids, extras and `fileapex://open` links that tie a launcher or share-sheet shortcut to a device, friend or the Bulletin Board. */
object DirectShareShortcutIds {
    const val CATEGORY_SHARE_TARGET = "com.fileapex.category.SHARE_TARGET"
    const val EXTRA_TARGET_DEVICE_ID = "com.fileapex.extra.TARGET_DEVICE_ID"
    const val EXTRA_OPEN_BULLETIN = "com.fileapex.extra.OPEN_BULLETIN"
    /** Matches [android.content.pm.ShortcutManager.EXTRA_SHORTCUT_ID] on API 29+. */
    const val EXTRA_SHORTCUT_ID = "android.intent.extra.shortcut.ID"
    const val BULLETIN_SHORTCUT_ID = "share-bulletin-board"
    const val OPEN_URI_SCHEME = "fileapex"
    const val OPEN_URI_HOST = "open"

    sealed class LauncherDestination {
        data object BulletinBoard : LauncherDestination()
        data class Device(val deviceId: String) : LauncherDestination()
    }

    internal const val SHORTCUT_PREFIX = "share-device-"
    internal const val FRIEND_PREFIX = "share-friend-"

    fun shortcutId(deviceId: String): String = "$SHORTCUT_PREFIX$deviceId"

    fun deviceIdFromShortcutId(shortcutId: String?): String? {
        if (shortcutId.isNullOrBlank() || shortcutId == BULLETIN_SHORTCUT_ID) return null
        if (!shortcutId.startsWith(SHORTCUT_PREFIX)) return null
        return shortcutId.removePrefix(SHORTCUT_PREFIX).takeIf { it.isNotBlank() }
    }

    fun friendKeyFromShortcutId(shortcutId: String?): String? =
        shortcutId?.takeIf { it.startsWith(FRIEND_PREFIX) }?.removePrefix(FRIEND_PREFIX)?.takeIf { it.isNotBlank() }

    fun isBulletinShortcut(shortcutId: String?): Boolean = shortcutId == BULLETIN_SHORTCUT_ID

    fun bulletinOpenUri(): Uri = Uri.Builder()
        .scheme(OPEN_URI_SCHEME)
        .authority(OPEN_URI_HOST)
        .appendPath("bulletin")
        .build()

    fun deviceOpenUri(deviceId: String): Uri = Uri.Builder()
        .scheme(OPEN_URI_SCHEME)
        .authority(OPEN_URI_HOST)
        .appendPath("device")
        .appendPath(deviceId)
        .build()

    fun parseOpenUri(uri: Uri?): LauncherDestination? {
        if (uri == null) return null
        if (uri.scheme?.lowercase() != OPEN_URI_SCHEME) return null
        if (uri.host?.lowercase() != OPEN_URI_HOST) return null
        val segments = uri.pathSegments
        return when (segments.firstOrNull()?.lowercase()) {
            "bulletin" -> LauncherDestination.BulletinBoard
            "device" -> {
                val deviceId = segments.getOrNull(1)?.trim().orEmpty()
                if (deviceId.isEmpty()) null else LauncherDestination.Device(deviceId)
            }
            else -> null
        }
    }

    /**
     * Launcher long-press only. Share-sheet Direct Share stays on ACTION_SEND +
     * [EXTRA_SHORTCUT_ID] and must not be parsed here.
     */
    fun parseLauncherDestination(intent: Intent?): LauncherDestination? {
        if (intent == null || AndroidShareIntake.isShareAction(intent)) return null
        parseOpenUri(intent.data)?.let { return it }
        if (intent.getBooleanExtra(EXTRA_OPEN_BULLETIN, false)) {
            return LauncherDestination.BulletinBoard
        }
        val deviceId = intent.getStringExtra(EXTRA_TARGET_DEVICE_ID)?.trim().orEmpty()
        return if (deviceId.isEmpty()) null else LauncherDestination.Device(deviceId)
    }
}
