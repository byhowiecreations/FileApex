package com.fileapex.membership

import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.util.cancellableCatching
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What a device says about its membership state in one short value: how far its chain goes, the first 128 bits of the
 * head, and a digest of its own stable profile. It decides only whether to ask; a peer's records are still verified by signature.
 */
data class ChainStateField(val seq: Long, val head: String, val profile: String) {
    fun encode(): String = "v1.$seq.$head.$profile"

    /** The profile digest as a number, for the node-state fetch that already runs when a peer's version changes. */
    val profileVersion: Long get() = profile.toLong(16)

    companion object {
        const val HEADER = "X-FileApex-Chain-State"
        const val QUERY = "cs"
        const val MAX_LENGTH = 128
        private const val HEAD_CHARS = 32
        private val PATTERN = Regex("^v1\\.([0-9]{1,9})\\.([0-9a-f]{0,$HEAD_CHARS})\\.([0-9a-f]{8})$")

        fun of(seq: Long, head: String, profile: String) = ChainStateField(seq, head.take(HEAD_CHARS), profile)

        /** The field in [raw], or null for anything that is not exactly one well-formed value. */
        fun parse(raw: String?): ChainStateField? {
            if (raw == null || raw.length > MAX_LENGTH) return null
            val m = PATTERN.matchEntire(raw) ?: return null
            val seq = m.groupValues[1].toLong()
            val head = m.groupValues[2]
            if ((seq == 0L) != head.isEmpty() || (head.isNotEmpty() && head.length != HEAD_CHARS)) return null
            return ChainStateField(seq, head, m.groupValues[3])
        }
    }
}

/**
 * Eight hex characters over the fields of a device's own profile that change rarely: name, port, versions, platform,
 * keys, pins. Not the address, not presence or last-seen, and nothing relayed about another device, so a roaming phone
 * does not change it.
 */
object ProfileDigest {
    fun of(state: PeerNodeState): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            state.deviceId, state.deviceName, state.port.toString(), state.resolvedClientVersion,
            state.resolvedClientVersionCode.toString(), state.platform, state.os, state.deviceMake, state.deviceModel,
            state.supportedProtocols.joinToString(","), state.rootPath, state.publicKeyHash, state.publicKey,
            state.pinRequired.toString(), state.tlsPin, state.tlsPort.toString(), state.tlsRotation
        ).forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().take(4).joinToString("") { "%02x".format(it) }
    }
}

/**
 * This device's state field, from its chain and its own profile. It is built for every health probe sent and every health
 * answer given, and opening a device or starting a transfer waits on that path, so nothing on it may wait for slow work:
 * the chain part comes from the cached view, and the profile digest, whose building reads the network interfaces and the
 * hardware, is answered from memory and rebuilt in the background once it is older than [PROFILE_TTL_MS]. A rename or a new
 * key shows in the digest within that time plus one rebuild. Only the very first call builds it in line.
 */
class ChainStateSource(
    private val log: MembershipChainLog,
    private val now: () -> Long = com.fileapex.util.TimeUtils::now,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ownProfile: suspend () -> PeerNodeState?
) {
    private val lock = Any()
    private var digest: String? = null
    private var builtAt = 0L
    private var rebuilding = false

    suspend fun current(): ChainStateField {
        val state = log.view().state
        return ChainStateField.of(state.seq, state.head, profileDigest())
    }

    private suspend fun profileDigest(): String {
        val t = now()
        val (known, rebuild) = synchronized(lock) {
            val held = digest
            val stale = held != null && t - builtAt >= PROFILE_TTL_MS && !rebuilding
            if (stale) rebuilding = true
            held to stale
        }
        if (known == null) return build(t) ?: NO_PROFILE
        if (rebuild) scope.launch { cancellableCatching { build(t) } }
        return known
    }

    private suspend fun build(at: Long): String? {
        try {
            val built = ownProfile()?.let(ProfileDigest::of)
            if (built != null) synchronized(lock) {
                digest = built
                builtAt = at
            }
            return built
        } finally {
            synchronized(lock) { rebuilding = false }
        }
    }

    private companion object {
        const val PROFILE_TTL_MS = 10_000L
        const val NO_PROFILE = "00000000"
    }
}
