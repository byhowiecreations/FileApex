package com.fileapex.cloud.diagnostics

/** User-facing errors for encrypted cloud Device Details relay. */
object DiagnosticsRelayErrors {
    fun localOptInRequired(): String =
        "Enable \"Allow over cellular\" in Settings → Device Details on this device."

    fun peerOptInRequired(): String =
        "That device has not enabled cloud Device Details (Settings → Device Details)."

    fun googleLinkRequired(): String =
        "Link a Google Account on both devices to fetch Device Details over cellular."

    fun peerNotCloudLinked(): String =
        "That device is not in your Google-linked device registry."

    fun peerKeyMissing(): String =
        "That device has not published diagnostics encryption keys yet. " +
            "Ask the peer to toggle \"Allow over cellular\" on and off."

    fun timedOut(): String =
        "Peer did not respond within 15 seconds. Open FileApex on that device briefly and retry."

    fun peerFcmTokenMissing(): String =
        "That Android device has no cloud wake token. Open FileApex on it while Google-linked."

    fun peerWakeFailed(): String =
        "Could not wake that device. Open FileApex on it and retry."

    fun peerRespondedFailed(): String =
        "That device could not prepare device details. Ensure cloud Device Details is enabled."

    fun lanAndCloudUnavailable(): String =
        "Device details require the same Wi-Fi network, or cloud relay with \"Allow over cellular\" " +
            "enabled on both devices."

    fun firestorePermissionDenied(): String =
        "Cloud Device Details is blocked by Firestore security rules. " +
            "In Firebase Console → Firestore → Rules, add a diagnosticsRelay block and publish."

    /** Maps raw backend/Firestore failures to a short user message. */
    fun fromThrowable(error: Throwable): String {
        val raw = error.message.orEmpty()
        if (raw.contains("403") ||
            raw.contains("PERMISSION_DENIED", ignoreCase = true) ||
            raw.contains("Missing or insufficient permissions", ignoreCase = true)
        ) {
            return firestorePermissionDenied()
        }
        return raw.ifBlank { "Could not load device details over cellular" }
    }
}
