package com.fileapex.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.LocalAppLocale
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
    val lastSeenEpochMs: Long = 0L,
    val os: String = "",
    val platform: String = "",
    val deviceMake: String = "",
    val deviceModel: String = "",
    val cardPosX: Float? = null,
    val cardPosY: Float? = null,
    val cardSortOrder: Int = 0,
    val cardMenuOrder: String = "",
    val tilePosX: Float? = null,
    val tilePosY: Float? = null,
    val tileSortOrder: Int = 0,
    val tileMenuOrder: String = ""
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
            val status = if (online) AppI18n.t("ready") else AppI18n.t("tap_to_wake")
            val version = appVersion?.trim()?.takeIf { it.isNotEmpty() }
            val versionLabel = version?.let { versionText -> "v$versionText" }
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

        @Composable
        fun localizedSubtitle(row: DeviceListRow): String {
            LocalAppLocale.current
            return row.subtitle
        }

        @Composable
        fun localizedPeerStatus(
            online: Boolean,
            appVersion: String?,
            appVersionCode: Int = 0,
            lastSeenEpochMs: Long = 0L
        ): String {
            LocalAppLocale.current
            return peerStatusSubtitle(online, appVersion, appVersionCode, lastSeenEpochMs)
        }

        fun versionLabel(appVersion: String?, appVersionCode: Int = 0): String? {
            val version = appVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return "v$version"
        }
    }
}
