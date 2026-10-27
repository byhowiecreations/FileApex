package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.CandidateOrigin
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.PeerCandidate
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.domain.peer.PeerNodeStateMapper
import com.fileapex.domain.presence.LanPresenceTiming
import com.fileapex.domain.presence.LanScanBackoff
import com.fileapex.domain.presence.PeerLanDiscovery
import com.fileapex.network.FileApexClient
import com.fileapex.security.IdentityBinding
import com.fileapex.security.IntroductionResult
import com.fileapex.security.PeerIntroductions
import com.fileapex.security.SealedIntroduction
import com.fileapex.security.tls.ClipboardPairCrypto
import com.fileapex.security.tls.PairCrypto
import com.fileapex.security.tls.TlsPinAnnouncer
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

sealed interface CandidateVerdict {
    data object Promoted : CandidateVerdict
    data class Pending(val reason: String) : CandidateVerdict
    data class Rejected(val reason: String) : CandidateVerdict
}

/** Asks the node at an address who it is. Null when nothing answers. */
fun interface PeerStateFetcher {
    suspend fun fetch(host: String, port: Int): PeerNodeState?
}

/**
 * Decides who joins this device's roster. A peer is added only after this device has reached it itself.
 * Introductions and relayed states only create candidates; nothing here forwards a candidate to anyone.
 */
class PeerAdmission(
    private val repository: DeviceRepository,
    private val client: FileApexClient,
    private val identityProvider: () -> LocalIdentity,
    private val onSuperseded: suspend (PairedDeviceEntity) -> Unit = {},
    private val onPromoted: suspend (PairedDeviceEntity) -> Unit = {},
    private val now: () -> Long = TimeUtils::now,
    private val crypto: PairCrypto = ClipboardPairCrypto,
    private val fetchState: PeerStateFetcher = PeerStateFetcher { host, port ->
        try {
            client.fetchPeerNodeState(host, port, LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        }
    },
    private val discover: suspend (PairedDeviceEntity) -> PeerNodeState? = { device ->
        PeerLanDiscovery.discoverPeerState(device, client, LanPresenceTiming.LIGHT_SWEEP_DISCOVERY_BUDGET_MS)
    },
    private val scanBackoff: LanScanBackoff = LanScanBackoff(
        LanPresenceTiming.LAN_SCAN_BACKOFF_BASE_MS,
        LanPresenceTiming.LAN_SCAN_BACKOFF_MAX_MS,
        TimeUtils::now
    ),
    private val networkKey: () -> String = LanScanBackoff::currentNetworkKey
) {
    /** One sealed introduction per device this device has itself verified, addressed to [recipient]. */
    suspend fun sealedIntroductionsFor(
        recipient: PairedDeviceEntity,
        subjects: List<PairedDeviceEntity>? = null
    ): List<SealedIntroduction> {
        val localId = identityProvider().deviceId
        val roster = repository.listDevices()
        val pool = subjects ?: repository.introducibleDevices()
        return pool
            .filter { it.deviceId != recipient.deviceId && it.deviceId != localId }
            .filter { PeerTrustRules.mayIntroduce(it, roster) }
            .mapNotNull { subject -> PeerIntroductions.seal(localId, recipient, subject, now(), crypto) }
    }

    /** Stages every valid introduction, then tries to verify what it staged. Returns how many were staged. */
    suspend fun acceptIntroductions(
        introductions: List<SealedIntroduction>,
        excludeSubjectIds: Set<String> = emptySet(),
        verifyNow: Boolean = true
    ): Int {
        val localId = identityProvider().deviceId
        var staged = 0
        for (sealed in introductions) {
            val introducer = repository.getDevice(sealed.introducerId)
            val result = PeerIntroductions.open(sealed, introducer, localId, now(), crypto)
            if (result !is IntroductionResult.Accepted) continue
            val subject = result.introduction.subject
            if (subject.deviceId in excludeSubjectIds || repository.hasActiveDevice(subject.deviceId)) continue
            val entity = PeerNodeStateMapper.toEntity(subject).copy(clusterVersion = subject.membershipVersion)
            if (repository.stageCandidate(entity, result.introduction.introducerId, CandidateOrigin.INTRODUCED)) {
                staged++
            }
        }
        if (staged > 0 && verifyNow) verifyPending()
        return staged
    }

    /**
     * Node states from a peer that mutual TLS authenticated, for builds that cannot seal introductions.
     * A sender that is not an active member, or a state for someone already trusted, is ignored.
     */
    suspend fun acceptTransportStates(
        states: List<PeerNodeState>,
        authenticatedSenderId: String?,
        verifyNow: Boolean = true
    ): Int {
        val sender = authenticatedSenderId?.trim().orEmpty()
        if (sender.isEmpty() || !repository.hasActiveDevice(sender)) return 0
        val localId = identityProvider().deviceId
        var staged = 0
        for (state in states) {
            val id = state.deviceId.trim()
            if (id.isEmpty() || id == localId || id == sender || repository.hasActiveDevice(id)) continue
            if (!state.hasMembershipProtocol) continue
            val entity = PeerNodeStateMapper.toEntity(state).copy(clusterVersion = state.membershipVersion)
            if (repository.stageCandidate(entity, sender, CandidateOrigin.TRANSPORT)) staged++
        }
        if (staged > 0 && verifyNow) verifyPending()
        return staged
    }

    private val verifying = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Drops expired candidates and tries the rest, each on its own so a candidate that does not answer holds up
     * no other. A candidate tried within the last [LanPresenceTiming.CANDIDATE_RETRY_MS] is left alone unless
     * [force]. Returns how many were promoted.
     */
    suspend fun verifyPending(force: Boolean = false): Int {
        repository.expireCandidates()
        val promoted = java.util.concurrent.atomic.AtomicInteger()
        kotlinx.coroutines.coroutineScope {
            for (candidate in repository.listCandidates()) {
                if (!force && candidate.attempts > 0 &&
                    now() - candidate.lastAttemptEpochMs < LanPresenceTiming.CANDIDATE_RETRY_MS
                ) continue
                if (!verifying.add(candidate.deviceId)) continue
                launch {
                    try {
                        val verdict = try {
                            verify(candidate)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            println("PeerAdmission: verify ${candidate.deviceId} failed - ${error::class.simpleName}")
                            CandidateVerdict.Pending("error")
                        }
                        when (verdict) {
                            CandidateVerdict.Promoted -> promoted.incrementAndGet()
                            is CandidateVerdict.Pending -> repository.recordCandidateAttempt(candidate.deviceId)
                            is CandidateVerdict.Rejected -> {
                                println("PeerAdmission: rejected ${candidate.deviceId} - ${verdict.reason}")
                                repository.dropCandidate(candidate.deviceId, remember = true)
                            }
                        }
                    } finally {
                        verifying.remove(candidate.deviceId)
                    }
                }
            }
        }
        return promoted.get()
    }

    suspend fun verify(candidate: PeerCandidate): CandidateVerdict {
        val device = candidate.device
        val id = device.deviceId
        if (repository.hasActiveDevice(id)) {
            repository.dropCandidate(id)
            return CandidateVerdict.Rejected("already a member")
        }
        val live = reachCandidate(candidate) ?: return CandidateVerdict.Pending("not reachable yet")

        mismatch(candidate, live)?.let { return CandidateVerdict.Rejected(it) }

        val host = live.second
        val state = live.first
        val roster = repository.listDevices()
        val twin = roster.firstOrNull { PeerTrustRules.samePublicKey(it, state.asEntity(host)) }
        if (twin != null) {
            if (answersAs(twin)) return CandidateVerdict.Rejected("key held by ${twin.deviceId}")
            // The other row of this key is silent. Drop that row only; the key itself stays usable.
            repository.deleteSilentCopy(twin.deviceId)
        }

        if (!repository.promoteCandidate(candidate, state)) {
            return CandidateVerdict.Rejected("could not be added")
        }
        repository.getDevice(id)?.let { onPromoted(it) }
        TlsPinAnnouncer.nudge(id)
        return CandidateVerdict.Promoted
    }

    /** The candidate's own answer, and the address it came from. */
    private suspend fun reachCandidate(candidate: PeerCandidate): Pair<PeerNodeState, String>? {
        val device = candidate.device
        val host = device.lastKnownIp.trim()
        if (NetworkUtils.isUsableLanIpv4(host) && device.port > 0) {
            val state = fetchState.fetch(host, device.port)
            if (state != null) {
                scanBackoff.recordSuccess(device.deviceId)
                return state to host
            }
        }
        val key = networkKey()
        if (!scanBackoff.shouldScan(device.deviceId, key)) return null
        val found = discover(device)
        if (found == null) {
            scanBackoff.recordFailure(device.deviceId, key)
            return null
        }
        scanBackoff.recordSuccess(device.deviceId)
        val foundHost = found.resolvedIpAddress.trim()
        return if (NetworkUtils.isUsableLanIpv4(foundHost)) found to foundHost else null
    }

    /** Null when the candidate's own answer matches what it was introduced as. */
    private fun mismatch(candidate: PeerCandidate, live: Pair<PeerNodeState, String>): String? {
        val device = candidate.device
        val state = live.first
        if (state.deviceId.trim() != device.deviceId) return "answered as ${state.deviceId.trim()}"
        val key = state.publicKey.trim()
        if (device.publicKey.isNotBlank() && key != device.publicKey) return "public key differs"
        if (IdentityBinding.isDerived(device.deviceId) && !IdentityBinding.matches(device.deviceId, key)) {
            return "id is not derived from its key"
        }
        val vouched = candidate.origin == CandidateOrigin.INTRODUCED || candidate.origin == CandidateOrigin.TRANSPORT
        if (vouched && !PeerTrustRules.answersOnClusterClock(state.membershipProtocol, state.membershipVersion)) {
            return "not on the cluster clock"
        }
        val claimed = device.publicKeyHash.trim()
        val answered = state.publicKeyHash.trim()
        if (claimed.isNotEmpty() && answered.isNotEmpty() && claimed != answered) return "key hash differs"
        return null
    }

    /** Removes an outdated row for good and tells the cluster, e.g. the old entry of a device that was reinstalled. */
    suspend fun supersede(oldDeviceId: String): Boolean {
        if (!repository.removePermanently(oldDeviceId)) return false
        repository.getDeviceEntry(oldDeviceId)?.let { onSuperseded(it) }
        return true
    }

    /**
     * [state] came from a paired device over TLS. A row with the same hardware hash under another id is the same
     * physical device before a reinstall (new keys, new id): it is replaced when it no longer answers as itself, and
     * both are kept when both answer (a cloned machine can share its id). Returns how many rows were replaced.
     */
    suspend fun supersedeHardwareTwins(state: PeerNodeState): Int {
        val hash = state.hardwareHash.trim()
        if (hash.isEmpty()) return 0
        var replaced = 0
        for (twin in repository.activeDevicesWithHardware(hash, state.deviceId.trim())) {
            if (answersAs(twin)) continue
            if (supersede(twin.deviceId)) replaced++
        }
        return replaced
    }

    private suspend fun answersAs(row: PairedDeviceEntity): Boolean {
        val host = row.lastKnownIp.trim()
        if (!NetworkUtils.isUsableLanIpv4(host) || row.port <= 0) return false
        val state = fetchState.fetch(host, row.port) ?: return false
        return state.deviceId.trim() == row.deviceId
    }

    private fun PeerNodeState.asEntity(host: String): PairedDeviceEntity =
        PeerNodeStateMapper.toEntity(this).copy(lastKnownIp = host)
}
