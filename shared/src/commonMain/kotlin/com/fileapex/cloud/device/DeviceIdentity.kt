package com.fileapex.cloud.device

import com.fileapex.cloud.CloudAuthBackend
import com.fileapex.cloud.FcmWakeBackend
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.clipboard.ClipboardE2ee
import com.fileapex.security.IdentityBinding
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App wiring for the per-device Firebase identity (plan: docs/planning/per-device-firebase-identity-plan.md). Every
 * call is best effort: a device the relay will not enrol yet stays on its Google session, which still works. The relay's
 * allow-list is rebuilt from the local roster and removal records on every call, so a missed call heals on the next one.
 */
object DeviceIdentity {
    private const val ATTEMPT_GAP_MS = 10 * 60_000L
    private const val PAIRING_RETRY_MS = 20_000L
    private const val KEY_HASH_CHARS = 16
    private const val ANNOUNCE_TIMEOUT_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var lastAttemptMs = 0L

    private val service by lazy {
        DeviceIdentityService(
            relay = DeviceRelayClient(
                httpClient = { FileApexServices.httpClient },
                idToken = { CloudAuthBackend.firebaseIdToken() }
            ),
            idToken = { CloudAuthBackend.firebaseIdToken() },
            adoptToken = { CloudAuthBackend.adoptDeviceToken(it) },
            authenticate = { data, relayKey ->
                ClipboardE2ee.authenticate(data, selfId(), EnrolProof.RELAY_ID, relayKey)
            },
            nowSeconds = { TimeUtils.now() / 1000L }
        )
    }

    private fun selfId(): String = loadLocalIdentity().deviceId

    /** A key-derived id is `fx-` plus 32 hex digits of the key digest, and the key hash is the first 16 of those. */
    private fun keyHashOf(deviceId: String): String? =
        deviceId.takeIf { IdentityBinding.isDerived(it) }?.removePrefix(IdentityBinding.DERIVED_PREFIX)?.take(KEY_HASH_CHARS)
            ?.takeIf { it.length == KEY_HASH_CHARS }

    /**
     * Called once the cloud record (with its clipboard key) is published and after a pairing; at most every ten
     * minutes unless [force]. Enrols this device if needed, then reconciles the relay's list. Nothing runs on a timer.
     */
    internal suspend fun ensureEnrolled(force: Boolean = false): EnrolOutcome {
        val now = TimeUtils.now()
        if (!force && now - lastAttemptMs < ATTEMPT_GAP_MS) return EnrolOutcome.ALREADY
        lastAttemptMs = now
        val outcome = cancellableCatching { service.ensureEnrolled(selfId()) }.getOrElse {
            EnrolOutcome.UNREACHABLE
        }
        if (outcome == EnrolOutcome.ENROLLED || outcome == EnrolOutcome.ALREADY) reconcile()
        return outcome
    }

    /** Sends the paired peers (admit) and the removed ones (revoke) with their cluster versions; [extraAdmit] adds a fresh pairing. */
    internal suspend fun reconcile(extraAdmit: List<AdmitEntry> = emptyList()) {
        val repository = FileApexServices.deviceRepositoryOrNull() ?: return
        cancellableCatching {
            val self = selfId()
            val admit = (
                repository.listDevices().mapNotNull { peer ->
                    val hash = keyHashOf(peer.deviceId)
                    if (hash == null || peer.clusterVersion <= 0L) null else AdmitEntry(peer.deviceId, hash, peer.clusterVersion)
                } + extraAdmit
                ).filter { it.deviceId != self }.groupBy { it.deviceId }.map { (_, entries) -> entries.maxBy { it.version } }
            val revoke = repository.removedDeviceVersions().filterKeys { it != self }
                .map { RevokeEntry(it.key, it.value) }.sortedByDescending { it.version }
            service.reconcile(self, admit, revoke)
        }
    }

    /** A device was paired: admit it with a fresh version, and try to enrol this device now that a peer is there to vouch for it. */
    fun onPaired(peerId: String) {
        scope.launch {
            keyHashOf(peerId)?.let { reconcile(listOf(AdmitEntry(peerId, it, TimeUtils.now()))) }
            if (ensureEnrolled(force = true) == EnrolOutcome.REFUSED) {
                delay(PAIRING_RETRY_MS)
                ensureEnrolled(force = true)
            }
        }
    }

    private val kickWatch by lazy {
        KickWatch(
            check = { CloudAuthBackend.firebaseIdToken(forceRefresh = true) },
            stillLinked = { FileApexServices.settings.googleAccountLinkEnabled.value }
        )
    }

    private val announcer by lazy {
        RemovalAnnouncer(
            pushTokenOf = { cloudId ->
                CloudAuthBackend.fetchCloudDeviceRecord(FileApexServices.settings.googleAccountUid.value, cloudId)?.fcmToken
            },
            revoke = { reconcile() },
            push = { FcmWakeBackend.sendDeviceRemovedWake(it, selfId()) }
        )
    }

    /**
     * This device was told it was removed (a push, the removal announcement or a `revoked` answer from a peer). The
     * announcement is a nudge, not proof: Firebase's answer that the user is disabled is what signs this device out.
     */
    fun onKicked() {
        scope.launch { cancellableCatching { kickWatch.run() } }
    }

    /** A peer was removed: the relay disables its Firebase user, then the peer is pushed. Call before its cloud record is deleted. */
    suspend fun announceRemoval(cloudId: String) {
        cancellableCatching { withTimeoutOrNull(ANNOUNCE_TIMEOUT_MS) { announcer.announce(cloudId) } }
    }
}
