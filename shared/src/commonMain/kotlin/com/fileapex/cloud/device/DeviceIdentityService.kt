package com.fileapex.cloud.device

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive

internal enum class EnrolOutcome { ENROLLED, ALREADY, REFUSED, UNREACHABLE, SIGNED_OUT }

/** SENT: the relay has the lists. SKIPPED: nothing to do or not allowed to yet. FAILED: the relay could not be reached or refused. */
internal enum class SyncOutcome { SENT, SKIPPED, FAILED }

/**
 * Per-device Firebase identity on the client: enrols this device with the relay (proving the key its id comes from),
 * swaps the Google session for the per-device one, and keeps the relay's allow-list in line with the roster.
 */
internal class DeviceIdentityService(
    private val relay: DeviceRelay,
    private val idToken: suspend () -> String?,
    private val adoptToken: suspend (String) -> Unit,
    private val authenticate: (data: ByteArray, relayKey: String) -> ByteArray,
    private val nowSeconds: () -> Long,
    private val newNonce: () -> String = { EnrolProof.newNonce() }
) {
    private val mutex = Mutex()
    private var lastSynced: Pair<List<AdmitEntry>, List<RevokeEntry>>? = null

    suspend fun ensureEnrolled(selfId: String): EnrolOutcome = mutex.withLock {
        val token = idToken() ?: return EnrolOutcome.SIGNED_OUT
        if (IdTokenClaims.isDeviceSession(token, selfId)) return EnrolOutcome.ALREADY
        val relayKey = relay.relayKey() ?: return EnrolOutcome.UNREACHABLE
        for (attempt in 1..CONFLICT_RETRIES) {
            val ts = nowSeconds()
            val nonce = newNonce()
            val tag = EnrolProof.tag(selfId, ts, nonce) { authenticate(it, relayKey) }
            when (val answer = relay.enrol(selfId, ts, nonce, tag)) {
                is RelayAnswer.Ok -> {
                    val custom = (answer.body["token"] as? JsonPrimitive)?.content
                    if (custom.isNullOrBlank()) return EnrolOutcome.REFUSED
                    adoptToken(custom)
                    lastSynced = null
                    return EnrolOutcome.ENROLLED
                }
                is RelayAnswer.Refused -> if (answer.status != CONFLICT) return EnrolOutcome.REFUSED
                RelayAnswer.Unreachable -> return EnrolOutcome.UNREACHABLE
            }
        }
        EnrolOutcome.REFUSED
    }

    /**
     * Tells the relay which peers this device has paired and which it removed. Only a device session may; the same
     * lists are not sent twice in a row.
     */
    suspend fun reconcile(selfId: String, admit: List<AdmitEntry>, revoke: List<RevokeEntry>): SyncOutcome = mutex.withLock {
        val token = idToken() ?: return SyncOutcome.SKIPPED
        if (!IdTokenClaims.isDeviceSession(token, selfId)) return SyncOutcome.SKIPPED
        val lists = admit.take(MAX_ADMIT) to revoke.take(MAX_REVOKE)
        if (lastSynced == lists) return SyncOutcome.SKIPPED
        val answer = relay.sync(lists.first, lists.second)
        if (answer !is RelayAnswer.Ok) return SyncOutcome.FAILED
        lastSynced = lists
        SyncOutcome.SENT
    }

    companion object {
        private const val MAX_ADMIT = 50
        private const val MAX_REVOKE = 100
        private const val CONFLICT = 409
        private const val CONFLICT_RETRIES = 3
    }
}
