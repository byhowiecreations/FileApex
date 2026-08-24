package com.fileapex.cloud.diagnostics

import com.fileapex.i18n.AppI18n

/** User-facing errors for encrypted cloud Device Details relay. */
object DiagnosticsRelayErrors {
    fun localOptInRequired(): String = AppI18n.t("relay_local_opt_in")

    fun peerOptInRequired(): String = AppI18n.t("relay_peer_opt_in")

    fun googleLinkRequired(): String = AppI18n.t("relay_google_link_required")

    fun peerNotCloudLinked(): String = AppI18n.t("relay_peer_not_cloud_linked")

    fun peerKeyMissing(): String = AppI18n.t("relay_peer_key_missing")

    fun timedOut(): String = AppI18n.t("relay_timed_out")

    fun peerFcmTokenMissing(): String = AppI18n.t("relay_peer_fcm_token_missing")

    fun peerWakeFailed(): String = AppI18n.t("relay_peer_wake_failed")

    fun peerRespondedFailed(): String = AppI18n.t("relay_peer_responded_failed")

    fun lanAndCloudUnavailable(): String = AppI18n.t("relay_lan_and_cloud_unavailable")

    fun firestorePermissionDenied(): String = AppI18n.t("relay_firestore_permission_denied")

    /** Maps raw backend/Firestore failures to a short user message. */
    fun fromThrowable(error: Throwable): String {
        val raw = error.message.orEmpty()
        if (raw.contains("403") ||
            raw.contains("PERMISSION_DENIED", ignoreCase = true) ||
            raw.contains("Missing or insufficient permissions", ignoreCase = true)
        ) {
            return firestorePermissionDenied()
        }
        return raw.ifBlank { AppI18n.t("relay_load_failed") }
    }
}
