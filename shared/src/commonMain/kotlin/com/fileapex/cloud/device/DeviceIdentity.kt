package com.fileapex.cloud.device

import com.fileapex.cloud.CloudAuthBackend
import com.fileapex.cloud.FcmWakeBackend
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.clipboard.ClipboardE2ee
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App wiring for the per-device Firebase identity (plans: docs/planning/per-device-firebase-identity-plan.md,
 * docs/planning/one-trust-path-plan.md). Every call is best effort. The relay decides by the signed membership chain,
 * which this device sends it; a missed upload heals on the next one, because the relay asks only for what it lacks.
 */
object DeviceIdentity {
    private const val ATTEMPT_GAP_SECONDS = 10 * 60L
    private val APPROVAL_RECHECKS_MS = listOf(20_000L, 60_000L, 180_000L, 600_000L)
    private const val ANNOUNCE_TIMEOUT_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val olderSetup = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** True while the relay holds an older setup of this Google account; the Google account page then offers to start over. */
    val otherSetup: kotlinx.coroutines.flow.StateFlow<Boolean> = olderSetup

    @Volatile
    private var startOverWaits = false

    /** What the user is told while the cloud stays closed after [outcome]. */
    internal suspend fun closedReason(outcome: EnrolOutcome): String = when {
        outcome == EnrolOutcome.OTHER_SETUP && startOverWaits -> "Start over is possible one day after the older setup was last used"
        outcome == EnrolOutcome.OTHER_SETUP -> "This Google account is linked to an older FileApex setup"
        (FileApexServices.membershipHub?.chainLog?.view()?.state?.seq ?: 0L) == 0L -> "Pair a second device to use your Google account across devices"
        else -> "Waiting for a paired device to approve this device"
    }

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
            publicKey = { ClipboardE2ee.publicKeyBase64() },
            chain = { FileApexServices.membershipHub?.chainLog?.view()?.canonical.orEmpty() },
            nowSeconds = { TimeUtils.now() / 1000L },
            attemptGapSeconds = ATTEMPT_GAP_SECONDS
        )
    }

    private fun selfId(): String = loadLocalIdentity().deviceId

    /**
     * Called before the first Firestore call of a cloud session and after a pairing; the relay is asked at most every
     * ten minutes unless [force]. Enrols this device if needed (it hands its own key, so no cloud record is needed
     * first, and its chain goes with it), then sends the relay the chain records it lacks. Nothing runs on a timer.
     */
    internal suspend fun ensureEnrolled(force: Boolean = false): EnrolOutcome {
        val outcome = cancellableCatching { service.ensureEnrolled(selfId(), force) }.getOrElse {
            com.fileapex.platform.Diagnostics.log("DeviceIdentity: enrol failed on this device: ${it::class.qualifiedName}")
            EnrolOutcome.UNREACHABLE
        }
        com.fileapex.platform.Diagnostics.log("DeviceIdentity: enrol $outcome")
        if (outcome == EnrolOutcome.OTHER_SETUP) olderSetup.value = true
        if (outcome == EnrolOutcome.ENROLLED || outcome == EnrolOutcome.ALREADY) uploadChain()
        return outcome
    }

    /** Sends the relay the records of this device's chain it lacks; the relay switches removed members off from them. */
    internal suspend fun uploadChain() {
        val outcome = cancellableCatching { service.uploadChain() }.getOrNull()
        com.fileapex.platform.Diagnostics.log("DeviceIdentity: chain upload ${outcome ?: SyncOutcome.FAILED}")
        if (outcome == SyncOutcome.OTHER_SETUP) olderSetup.value = true
        if (outcome == SyncOutcome.SENT) olderSetup.value = false
    }

    /**
     * The user chose to start over, right after a fresh Google sign-in: the relay replaces the older setup of this
     * account with this device's chain and switches every older device off.
     */
    internal suspend fun startOver(): SyncOutcome {
        val outcome = cancellableCatching { service.startOver() }.getOrElse { SyncOutcome.FAILED }
        com.fileapex.platform.Diagnostics.log("DeviceIdentity: start over $outcome")
        startOverWaits = outcome == SyncOutcome.SETUP_IN_USE
        if (outcome == SyncOutcome.SENT) olderSetup.value = false
        return outcome
    }

    /**
     * A device was paired: the chain that admits it goes to the relay, and this device enrols if it is not yet. The
     * admit may reach this device a little later, so a waiting device checks again a few times over about fifteen
     * minutes; nothing runs without a pairing.
     */
    fun onPaired() {
        scope.launch {
            uploadChain()
            enrolAfterPairing()
            approvalWatch.run()
        }
    }

    /**
     * The membership chain finished loading at start-up. A cloud session that started before it had no chain to send,
     * so this device enrols and uploads again now (only when Google is linked).
     */
    fun onChainReady() {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        scope.launch { enrolAfterPairing() }
    }

    @Volatile
    private var awaitingApproval = false

    /** Enrols now; once enrolled, a device that waited for approval opens its cloud session. */
    private suspend fun enrolAfterPairing() {
        val outcome = ensureEnrolled(force = true)
        awaitingApproval = outcome == EnrolOutcome.REFUSED || outcome == EnrolOutcome.UNREACHABLE
        if (outcome == EnrolOutcome.ENROLLED) com.fileapex.cloud.GoogleLinkCoordinator.refreshCloudRegistry()
    }

    private val approvalWatch by lazy {
        RecheckWatch(check = { enrolAfterPairing() }, keepGoing = { awaitingApproval }, delaysMs = APPROVAL_RECHECKS_MS, quietMs = 0L)
    }

    private val kickWatch by lazy {
        RecheckWatch(
            check = { CloudAuthBackend.firebaseIdToken(forceRefresh = true) },
            keepGoing = { FileApexServices.settings.googleAccountLinkEnabled.value }
        )
    }

    private val announcer by lazy {
        RemovalAnnouncer(
            pushTokenOf = { cloudId ->
                CloudAuthBackend.fetchCloudDeviceRecord(FileApexServices.settings.googleAccountUid.value, cloudId)?.fcmToken
            },
            revoke = { uploadChain() },
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
