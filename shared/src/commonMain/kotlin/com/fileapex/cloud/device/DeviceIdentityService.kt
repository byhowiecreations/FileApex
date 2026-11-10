package com.fileapex.cloud.device

import com.fileapex.membership.ChainRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive

/**
 * [allowsCloud]: only a refusal (the relay will not admit this device yet) keeps Firestore closed. Unreachable and
 * signed out carry on with the Google session, which works while the legacy rules clause is published.
 */
internal enum class EnrolOutcome(val allowsCloud: Boolean) {
    ENROLLED(true), ALREADY(true), REFUSED(false), UNREACHABLE(true), SIGNED_OUT(true),

    /** The relay holds an older setup of this Google account: the user may start over with this one. */
    OTHER_SETUP(false)
}

/** SENT: the relay has the records. SKIPPED: nothing to send or signed out. FAILED: the relay could not be reached or refused. */
internal enum class SyncOutcome {
    SENT, SKIPPED, FAILED,

    /** The relay holds an older setup of this Google account. */
    OTHER_SETUP,

    /** Start over was refused: a device of the older setup was in contact within the last day. */
    SETUP_IN_USE
}

/**
 * Per-device Firebase identity on the client: enrols this device with the relay (proving the key its id comes from),
 * swaps the Google session for the per-device one, and sends the relay the signed membership chain it decides by.
 */
internal class DeviceIdentityService(
    private val relay: DeviceRelay,
    private val idToken: suspend () -> String?,
    private val adoptToken: suspend (String) -> Unit,
    private val authenticate: (data: ByteArray, relayKey: String) -> ByteArray,
    private val publicKey: () -> String,
    /** The membership chain that counts on this device, genesis first. */
    private val chain: suspend () -> List<ChainRecord>,
    private val nowSeconds: () -> Long,
    private val attemptGapSeconds: Long = 0L,
    private val newNonce: () -> String = { EnrolProof.newNonce() }
) {
    private val mutex = Mutex()
    private var lastAttempt: Pair<Long, EnrolOutcome>? = null

    /**
     * Enrols this device unless its session already is a device session. The relay is asked at most once per
     * [attemptGapSeconds] unless [force]; inside that gap the last answer is repeated, so a refusal is never mistaken
     * for enrolled.
     */
    suspend fun ensureEnrolled(selfId: String, force: Boolean = false): EnrolOutcome = mutex.withLock {
        val token = idToken() ?: return EnrolOutcome.SIGNED_OUT
        if (IdTokenClaims.isDeviceSession(token, selfId)) return EnrolOutcome.ALREADY
        val now = nowSeconds()
        val last = lastAttempt
        if (!force && last != null && now - last.first < attemptGapSeconds) return last.second
        return askRelay(selfId).also { lastAttempt = now to it }
    }

    private suspend fun askRelay(selfId: String): EnrolOutcome {
        val relayKey = relay.relayKey() ?: return EnrolOutcome.UNREACHABLE
        for (attempt in 1..CONFLICT_RETRIES) {
            val ts = nowSeconds()
            val nonce = newNonce()
            val tag = EnrolProof.tag(selfId, ts, nonce) { authenticate(it, relayKey) }
            when (val answer = relay.enrol(selfId, ts, nonce, tag, publicKey(), chain())) {
                is RelayAnswer.Ok -> {
                    val custom = (answer.body["token"] as? JsonPrimitive)?.content
                    if (custom.isNullOrBlank()) return EnrolOutcome.REFUSED
                    adoptToken(custom)
                    return EnrolOutcome.ENROLLED
                }
                // Only "not admitted" and "removed" style answers are refusals; an outage or a rate limit is not.
                is RelayAnswer.Refused -> when {
                    answer.reason == OTHER_SETUP_REASON -> return EnrolOutcome.OTHER_SETUP
                    answer.status == CONFLICT -> Unit
                    answer.status == FORBIDDEN || answer.status == NOT_FOUND -> return EnrolOutcome.REFUSED
                    else -> return EnrolOutcome.UNREACHABLE
                }
                RelayAnswer.Unreachable -> return EnrolOutcome.UNREACHABLE
            }
        }
        return EnrolOutcome.REFUSED
    }

    /**
     * Sends the relay the records of this device's chain that it lacks: those after its head when it holds the same
     * root and a head this chain contains, otherwise the whole chain. The relay checks every signature itself.
     */
    suspend fun uploadChain(): SyncOutcome = mutex.withLock {
        idToken() ?: return SyncOutcome.SKIPPED
        val records = chain()
        if (records.isEmpty()) return SyncOutcome.SKIPPED
        val held = relay.chainHead() ?: return SyncOutcome.FAILED
        val at = if (held.root == records.first().hash()) records.indexOfFirst { it.hash() == held.head } else -1
        val missing = if (at >= 0) records.drop(at + 1) else records
        if (missing.isEmpty()) return SyncOutcome.SKIPPED
        outcomeOf(relay.uploadChain(missing))
    }

    /**
     * The user chose to start over: the whole chain goes to the relay with the request to replace the older setup of
     * this account. The session must come from a Google sign-in of the last five minutes; the relay checks that.
     */
    suspend fun startOver(): SyncOutcome = mutex.withLock {
        idToken() ?: return SyncOutcome.SKIPPED
        val records = chain()
        if (records.isEmpty()) return SyncOutcome.SKIPPED
        outcomeOf(relay.uploadChain(records, startOver = true))
    }

    private fun outcomeOf(answer: RelayAnswer): SyncOutcome = when {
        answer is RelayAnswer.Ok -> SyncOutcome.SENT
        answer is RelayAnswer.Refused && answer.reason == OTHER_SETUP_REASON -> SyncOutcome.OTHER_SETUP
        answer is RelayAnswer.Refused && answer.reason == SETUP_IN_USE_REASON -> SyncOutcome.SETUP_IN_USE
        else -> SyncOutcome.FAILED
    }

    companion object {
        private const val CONFLICT = 409
        private const val OTHER_SETUP_REASON = "other_setup"
        private const val SETUP_IN_USE_REASON = "setup_in_use"
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val CONFLICT_RETRIES = 3
    }
}
