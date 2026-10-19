package com.fileapex.network

import com.fileapex.i18n.AppI18n

/**
 * User-facing copy when direct LAN is unavailable — shared across transfer, navigation,
 * and Device Details entry points.
 */
object PeerReachabilityMessages {

    fun localWifiRequired(): String = AppI18n.t("local_wifi_required")

    fun peerOffline(): String = AppI18n.t("unable_to_reach_device")
}
