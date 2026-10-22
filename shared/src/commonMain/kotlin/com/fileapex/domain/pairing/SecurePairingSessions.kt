package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.Deferred
import java.util.concurrent.ConcurrentHashMap

/** One comparison pairing in progress on the host. Only one runs at a time. */
class SecurePairingSession(
    val id: String,
    val joiner: PairedDeviceEntity,
    val joinerPin: String,
    val commit: String,
    val nonceA: String,
    val createdMs: Long
) {
    /** Set when the joiner revealed its value and the host's user was asked. */
    @Volatile var hostDecision: Deferred<Boolean?>? = null
}

object SecurePairingSessions {
    private const val TTL_MS = 3 * 60_000L
    private val sessions = ConcurrentHashMap<String, SecurePairingSession>()

    /** Null when another pairing is still in progress. */
    fun open(joiner: PairedDeviceEntity, joinerPin: String, commit: String): SecurePairingSession? {
        val now = TimeUtils.now()
        sessions.entries.removeIf { now - it.value.createdMs > TTL_MS }
        if (sessions.isNotEmpty()) return null
        val session = SecurePairingSession(
            id = SecurePairingProof.newNonceHex(),
            joiner = joiner,
            joinerPin = joinerPin,
            commit = commit,
            nonceA = PairingSas.newNonce(),
            createdMs = now
        )
        sessions[session.id] = session
        return session
    }

    fun get(id: String): SecurePairingSession? = sessions[id]

    fun close(id: String) {
        sessions.remove(id)
    }

    fun clear() = sessions.clear()
}
