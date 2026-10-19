package com.fileapex.domain.pairing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** A device that entered the pairing code and is waiting for this device's user to accept it. */
data class PendingJoin(
    val deviceId: String,
    val deviceName: String,
    val checkCode: String,
    /** What the joiner's hardware hash says about it (a reinstall of a paired device, a removed one, a look-alike name). */
    val hint: PairingHint = PairingHint.None
)

/** The code the joining user must compare with the one on the host's screen before anything is sent. */
data class JoinerCheck(val hostName: String, val checkCode: String)

/**
 * Both users confirm a pairing. The joining device shows the check code and waits for its user; the host shows the same
 * code when the joiner's request arrives and waits for its user. Nothing is adopted, and no key is stored, before both said yes.
 */
class PairingConfirmationBoard {
    private val hostPending = MutableStateFlow<PendingJoin?>(null)
    val pendingJoin: StateFlow<PendingJoin?> = hostPending.asStateFlow()
    private var hostDecision: CompletableDeferred<Boolean>? = null
    private val hostLock = Any()

    private val joinerPending = MutableStateFlow<JoinerCheck?>(null)
    val joinerCheck: StateFlow<JoinerCheck?> = joinerPending.asStateFlow()
    private var joinerDecision: CompletableDeferred<Boolean>? = null
    private val joinerLock = Any()

    /** True when the host user accepted, false on decline or no answer in time, null when another joiner is already waiting. */
    suspend fun askHost(join: PendingJoin, timeoutMs: Long): Boolean? {
        val decision = CompletableDeferred<Boolean>()
        synchronized(hostLock) {
            if (hostDecision != null) return null
            hostDecision = decision
            hostPending.value = join
        }
        try {
            return withTimeoutOrNull(timeoutMs) { decision.await() } ?: false
        } finally {
            synchronized(hostLock) {
                if (hostDecision === decision) {
                    hostDecision = null
                    hostPending.value = null
                }
            }
        }
    }

    fun decideHost(accepted: Boolean) {
        val decision = synchronized(hostLock) { hostDecision } ?: return
        decision.complete(accepted)
    }

    /** True when the joining user confirmed the code matches. */
    suspend fun askJoiner(check: JoinerCheck): Boolean {
        val decision = CompletableDeferred<Boolean>()
        synchronized(joinerLock) {
            joinerDecision?.complete(false)
            joinerDecision = decision
            joinerPending.value = check
        }
        try {
            return decision.await()
        } finally {
            synchronized(joinerLock) {
                if (joinerDecision === decision) {
                    joinerDecision = null
                    joinerPending.value = null
                }
            }
        }
    }

    fun decideJoiner(confirmed: Boolean) {
        val decision = synchronized(joinerLock) { joinerDecision } ?: return
        decision.complete(confirmed)
    }
}

object PairingConfirmations {
    val board = PairingConfirmationBoard()
    const val HOST_CONFIRM_TIMEOUT_MS = 90_000L
}
