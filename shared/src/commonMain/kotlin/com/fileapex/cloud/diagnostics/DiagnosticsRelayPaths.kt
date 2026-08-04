package com.fileapex.cloud.diagnostics

/** Firestore relay document under users/{uid}/diagnosticsRelay/{sessionId}. */
object DiagnosticsRelayPaths {
    fun collection(uid: String): String = "users/$uid/diagnosticsRelay"

    fun document(uid: String, sessionId: String): String =
        "${collection(uid)}/$sessionId"
}

object DiagnosticsRelayStatus {
    const val PENDING = "pending"
    const val COMPLETE = "complete"
    const val FAILED = "failed"
}

const val DIAGNOSTICS_RELAY_TTL_MS = 5 * 60 * 1000L
/** Max wait for encrypted relay response after FCM wake (Mac/desktop → Android). */
const val DIAGNOSTICS_RELAY_FETCH_TIMEOUT_MS = 15_000L
const val DIAGNOSTICS_RELAY_POLL_MS = 400L
