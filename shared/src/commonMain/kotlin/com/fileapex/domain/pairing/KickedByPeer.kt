package com.fileapex.domain.pairing

import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.cloud.seal.ClusterRecordKeys
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.BriefToast
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The one self-wipe of this device, run only for a verified `REMOVE` of this device on the chain that counts
 * ([onUserRemoved], called by the chain effects). It signs out of Google inside FileApex, as the Unlink button does,
 * drops the cluster key and lets go of its old roster and the cluster's data (Bulletin Board, transfers), then takes a
 * new TLS key. Friends and settings are kept, and the Android account is never touched. Each removal is acted on once.
 *
 * Everything else that says "you were removed" is a hint: a `401 revoked` answer and a disabled Firebase user only make
 * this device pull the chain, where a real removal is a signed record (docs/planning/one-trust-path-plan.md).
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

    /** Pulls the chain from one peer; a removal of this device in it reaches [onUserRemoved] through the applier. */
    internal var pullChainFrom: suspend (peerId: String) -> Unit = { id -> FileApexServices.membershipHub?.chainReplicator?.reconcile(id) }

    /** Pulls the chain from every roster peer. */
    internal var pullChainFromRoster: suspend () -> Unit = {
        FileApexServices.membershipHub?.let { hub -> hub.chainRoster().forEach { cancellableCatching { hub.chainReplicator.reconcile(it) } } }
    }

    /**
     * A pinned roster peer ([peerId], tied to the answer by its TLS route) answered `401 revoked`. Its headers prove
     * nothing, so they are not read: this device pulls the chain from that peer, and only a signed removal found there
     * lets it go. An answer that cannot be tied to a pinned, active roster peer is ignored.
     */
    fun onRevoked(peerId: String?, roster: List<PairedDeviceEntity>) {
        val peer = roster.firstOrNull { !it.isRemoved && it.tlsPin.isNotEmpty() && it.deviceId == peerId } ?: return
        println("KickedByPeer: ${peer.deviceId} refuses this device: pulling the chain from it")
        scope.launch { cancellableCatching { pullChainFrom(peer.deviceId) } }
    }

    /**
     * Firebase says this device's cloud user is disabled. That ends the cloud session only: the Google link inside
     * FileApex is set aside, and the chain is pulled from the roster, where a real removal would be a signed record.
     */
    fun onFirebaseUserDisabled() {
        println("KickedByPeer: Firebase user is disabled: ending the cloud session and pulling the chain")
        if (!googleLinked() || !signingOut.compareAndSet(false, true)) return
        scope.launch {
            try {
                cancellableCatching { signOut() }
                    .onFailure { println("KickedByPeer: Google sign-out failed - ${it::class.simpleName}") }
                cancellableCatching { pullChainFromRoster() }
            } finally {
                signingOut.set(false)
            }
        }
    }

    private val signingOut = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The chain that counts holds a signed `REMOVE` of this device by [by]: acted on once per [stamp]. */
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
