package com.fileapex.domain.pairing

import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.cloud.seal.ClusterRecordKeys
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.BriefToast
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A removed device that reaches a roster peer is answered `401 revoked` with the stamp of the peer's tombstone. Only an
 * answer that says `user_removed` is acted on; one without a reason (an old build) or with another reason is logged.
 * Then this device signs out of Google inside FileApex, as the Unlink button does, drops the cluster
 * key and lets go of its old roster and the cluster's data (Bulletin Board, transfers), so pairing again joins fresh instead
 * of announcing itself to peers that dropped it. Friends and settings are kept, and the Android account is never touched. Each stamp is acted on once, so signing in again
 * on purpose is not undone.
 */
object KickedByPeer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal var signOut: suspend () -> Unit = { GoogleLinkCoordinator.unlinkAndSignOut() }
    internal var googleLinked: () -> Boolean = { FileApexServices.settings.googleAccountLinkEnabled.value }
    internal var handledVersion: () -> Long = { FileApexServices.settings.kickHandledVersion() }
    internal var markHandled: (Long) -> Unit = { FileApexServices.settings.setKickHandledVersion(it) }
    internal var dropClusterKey: () -> Unit = { ClusterRecordKeys.forget() }
    internal var announce: () -> Unit = { BriefToast.show(AppI18n.t("removed_signed_out_google")) }
    internal var forgetCluster: suspend () -> Int = { wipeCluster() }
    internal var purgeData: suspend () -> Unit = { FileApexServices.purgeClusterData() }

    /** Drops the signed chain and the old roster so a later pairing cannot fork against the previous genesis. */
    internal suspend fun wipeCluster(): Int {
        FileApexServices.membershipHub?.chainLog?.clear()
        val dropped = FileApexServices.deviceRepository.forgetCluster()
        purgeData()
        return dropped
    }
    internal var renewTlsIdentity: suspend () -> Unit = { com.fileapex.security.tls.TlsIdentityRenewal.renew() }
    internal var announceForgot: () -> Unit = { BriefToast.show(AppI18n.t("removed_from_cluster")) }

    /**
     * [peerId] is the pinned peer the answer came from, whatever route it was reached by, or null when the route
     * could not be tied to one. [revokedAt] is 0 when the answer carried no stamp (a route that does not read
     * headers): nothing is done then.
     */
    fun onRevoked(
        peerId: String?,
        revokedAt: Long,
        roster: List<PairedDeviceEntity>,
        nowMs: Long = TimeUtils.now(),
        reason: String = "",
        removedBy: String = ""
    ) {
        val peer = roster.firstOrNull { !it.isRemoved && it.tlsPin.isNotEmpty() && it.deviceId == peerId } ?: return
        decide(peer, revokedAt, reason, removedBy, nowMs)
    }

    /**
     * A removal is acted on only when it says `user_removed`. An answer with another reason, or none (an old build,
     * which cannot sign), is a hint that is logged and changes nothing.
     */
    private fun decide(peer: PairedDeviceEntity, stamp: Long, reason: String, removedBy: String, nowMs: Long) {
        if (reason == RemovalReason.USER_REMOVED.code) {
            com.fileapex.cloud.device.DeviceIdentity.onKicked()
            onUserRemoved(peer.deviceName, stamp)
            return
        }
        println(
            "KickedByPeer: ${peer.deviceId} says this device was removed (reason ${reason.ifBlank { "none" }}, by ${removedBy.ifBlank { "unknown" }}): hint only"
        )
    }

    /**
     * Firebase itself says this device's user is disabled: a roster device removed it through the relay. That needs no
     * second opinion from the peers, so this device signs out of Google inside FileApex at once. Signing in again on
     * purpose is not undone: the sign-in goes through a new enrol, and a disabled user cannot ask a second time.
     */
    fun onFirebaseUserDisabled() {
        println("KickedByPeer: Firebase user is disabled, revoked by a roster device (counted as user removed)")
        dropClusterKey()
        if (!googleLinked() || !signingOut.compareAndSet(false, true)) return
        scope.launch {
            try {
                letGoOfCluster()
                cancellableCatching { signOut() }
                    .onSuccess { announce() }
                    .onFailure { println("KickedByPeer: Google sign-out failed - ${it::class.simpleName}") }
            } finally {
                signingOut.set(false)
            }
        }
    }

    private val signingOut = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * The user pressed Remove for this device on a roster device that reached it directly ([RemovalNotice.judge] already
     * checked that). It is believed without a second opinion, once per removal stamp, and [by] is shown in the log.
     */
    fun onUserRemoved(by: String, stamp: Long) {
        if (stamp <= 0L || stamp <= handledVersion()) return
        println("KickedByPeer: removed by ${by.ifBlank { "a roster device" }}: user removed")
        confirmedRemoval(stamp)
    }

    private fun confirmedRemoval(stamp: Long) {
        markHandled(stamp)
        dropClusterKey()
        scope.launch {
            val dropped = letGoOfCluster()
            if (!googleLinked()) {
                if (dropped > 0) announceForgot()
                return@launch
            }
            cancellableCatching { signOut() }
                .onSuccess { announce() }
                .onFailure { println("KickedByPeer: Google sign-out failed - ${it::class.simpleName}") }
        }
    }

    /** The old cluster no longer trusts this device, so its roster is dropped and the next pairing is a fresh join. */
    private suspend fun letGoOfCluster(): Int {
        val dropped = cancellableCatching { forgetCluster() }
            .onFailure { println("KickedByPeer: could not set the old cluster aside - ${it::class.simpleName}") }
            .getOrDefault(0)
        cancellableCatching { renewTlsIdentity() }
            .onFailure { println("KickedByPeer: could not take a new TLS key - ${it::class.simpleName}") }
        return dropped
    }
}
