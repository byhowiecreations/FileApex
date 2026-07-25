package com.fileapex.presentation

import androidx.compose.runtime.Immutable
import com.fileapex.util.TimeUtils

/**
 * Stable list-cell model for the devices LazyColumn.
 */
@Immutable
data class DeviceListRow(
    val deviceId: String,
    val deviceName: String,
    val online: Boolean,
    val appVersion: String?,
    val appVersionCode: Int = 0,
    val lastSeenEpochMs: Long = 0L
) {
    val title: String get() = deviceName

    val subtitle: String get() = peerStatusSubtitle(
        online = online,
        appVersion = appVersion,
        appVersionCode = appVersionCode,
        lastSeenEpochMs = lastSeenEpochMs
    )

    companion object {
        fun areItemsTheSame(oldItem: DeviceListRow, newItem: DeviceListRow): Boolean =
            oldItem.deviceId == newItem.deviceId

        fun areContentsTheSame(oldItem: DeviceListRow, newItem: DeviceListRow): Boolean =
            oldItem == newItem

        fun peerStatusSubtitle(
            online: Boolean,
            appVersion: String?,
            appVersionCode: Int = 0,
            lastSeenEpochMs: Long = 0L
        ): String {
            val status = if (online) "Ready" else "Tap to wake"
            val version = appVersion?.trim()?.takeIf { it.isNotEmpty() }
            val versionLabel = version?.let { versionText ->
                val codeSuffix = appVersionCode.takeIf { it > 0 }?.let { " ($it)" }.orEmpty()
                "v$versionText$codeSuffix"
            }
            if (online) {
                return if (versionLabel != null) "$status · $versionLabel" else status
            }
            val lastSeen = TimeUtils.formatLastSeenLabel(lastSeenEpochMs)
            return buildList {
                add(status)
                if (versionLabel != null) add(versionLabel)
                if (lastSeen != null) add(lastSeen)
            }.joinToString(" · ")
        }

        fun versionLabel(appVersion: String?, appVersionCode: Int = 0): String? {
            val version = appVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val codeSuffix = appVersionCode.takeIf { it > 0 }?.let { " ($it)" }.orEmpty()
            return "v$version$codeSuffix"
        }
    }
}
