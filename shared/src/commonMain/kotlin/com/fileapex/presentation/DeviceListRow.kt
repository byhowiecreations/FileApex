package com.fileapex.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.fileapex.domain.presence.RouteKind
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
    /** What the card shows as the version when it differs from [appVersion] (a Docker node's real version); never used for logic. */
    val displayVersion: String? = null,
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
    val tileMenuOrder: String = "",
    /** Set only for a pinned friend shown as a row: the group name. A friend row has the id `friend:<key>`. */
    val friendGroup: String? = null,
    val friendAutoAccept: Boolean = false,
    /** The route the last contact used; only meaningful while [online]. */
    val reachedBy: RouteKind? = null
) {
    val title: String get() = deviceName

    val isFriend: Boolean get() = friendGroup != null

    val subtitle: String get() = friendGroup ?: peerStatusSubtitle(
        online = online,
        appVersion = displayVersion ?: appVersion,
        lastSeenEpochMs = lastSeenEpochMs,
        reachedBy = reachedBy
    )

    companion object {
        fun areItemsTheSame(oldItem: DeviceListRow, newItem: DeviceListRow): Boolean =
            oldItem.deviceId == newItem.deviceId

        fun areContentsTheSame(oldItem: DeviceListRow, newItem: DeviceListRow): Boolean {
            // Presence sweeps refresh lastSeen constantly while online; subtitle ignores it when Ready.
            if (oldItem.online && newItem.online) {
                return oldItem.copy(lastSeenEpochMs = 0L) == newItem.copy(lastSeenEpochMs = 0L)
            }
            return oldItem == newItem
        }

        fun peerStatusSubtitle(
            online: Boolean,
            appVersion: String?,
            lastSeenEpochMs: Long = 0L,
            reachedBy: RouteKind? = null
        ): String {
            val status = if (online) onlineLabel(reachedBy) else AppI18n.t("tap_to_wake")
            val version = appVersion?.trim()?.takeIf { it.isNotEmpty() }
            val versionLabel = version?.let { versionText -> "v$versionText" }
            if (online) {
                return if (versionLabel != null) "$status · $versionLabel" else status
            }
            val lastSeen = TimeUtils.formatLastSeenLabel(lastSeenEpochMs)
            val prefix = buildList {
                add(status)
                if (versionLabel != null) add(versionLabel)
            }.joinToString(" · ")
            return if (lastSeen != null) {
                if (prefix.isNotEmpty()) "$prefix\n$lastSeen" else lastSeen
            } else {
                prefix
            }
        }

        /** The short online status: the route name, or "Ready" before any route is known. */
        fun onlineLabel(reachedBy: RouteKind?): String = when (reachedBy) {
            RouteKind.Lan -> AppI18n.t("reach_lan")
            RouteKind.Tailnet -> AppI18n.t("reach_tailscale")
            RouteKind.Iroh -> AppI18n.t("reach_iroh")
            null -> AppI18n.t("ready")
        }

        /** The long form for Device Details; [irohPath] is `direct` or `relay` while an Iroh connection is open. */
        fun reachedByDetail(reachedBy: RouteKind, irohPath: String?): String = when (reachedBy) {
            RouteKind.Lan -> AppI18n.t("reached_lan_direct")
            RouteKind.Tailnet -> AppI18n.t("reached_tailscale")
            RouteKind.Iroh ->
                if (irohPath == "relay") AppI18n.t("reached_iroh_relay") else AppI18n.t("reached_iroh_direct")
        }

        @Composable
        fun localizedOnlineLabel(reachedBy: RouteKind?): String {
            LocalAppLocale.current
            return onlineLabel(reachedBy)
        }

        @Composable
        fun localizedSubtitle(row: DeviceListRow): String {
            LocalAppLocale.current
            return row.subtitle
        }
    }
}
