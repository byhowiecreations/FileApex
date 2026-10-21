package com.fileapex.cloud.diagnostics

object DiagnosticsRelayStatus {
    const val PENDING = "pending"
    const val COMPLETE = "complete"
    const val FAILED = "failed"
}

const val DIAGNOSTICS_RELAY_TTL_MS = 5 * 60 * 1000L
/** Max wait for encrypted relay response after FCM wake (Mac/desktop → Android). */
const val DIAGNOSTICS_RELAY_FETCH_TIMEOUT_MS = 15_000L
const val DIAGNOSTICS_RELAY_POLL_MS = 400L
/** A desktop has no push wake, so it looks for a request on this interval; the requester waits longer for it. */
const val DIAGNOSTICS_RELAY_DESKTOP_POLL_MS = 15_000L
const val DIAGNOSTICS_RELAY_DESKTOP_FETCH_TIMEOUT_MS = 30_000L
