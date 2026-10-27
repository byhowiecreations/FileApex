package com.fileapex.domain.pairing

import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.cloud.seal.ClusterRecordKeys
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.BriefToast
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Counts the `revoked` answers of pinned roster peers for one removal stamp. Two different peers, or one peer twice
 * at least [CONFIRM_GAP_MS] apart, count as a removal that is not a glitch.
 */
internal class KickEvidence {
    private val firstSeen = ConcurrentHashMap<Long, ConcurrentHashMap<String, Long>>()

    fun confirmed(revokedAt: Long, peerId: String, nowMs: Long): Boolean {
        val peers = firstSeen.computeIfAbsent(revokedAt) { ConcurrentHashMap() }
        peers.putIfAbsent(peerId, nowMs)
        return peers.size >= 2 || nowMs - peers.getValue(peerId) >= CONFIRM_GAP_MS
    }

    companion object {
        const val CONFIRM_GAP_MS = 2L * 60L * 1000L
    }
}

/**
 * A removed device that reaches a roster peer is answered `401 revoked` with the stamp of the peer's tombstone. Once
 * that is confirmed this device signs out of Google inside FileApex, as the Unlink button does, and drops the cluster
 * key. Local data is kept, and the Android account is never touched. Each stamp is acted on once, so signing in again
 * on purpose is not undone.
 */
object KickedByPeer {
    private val evidence = KickEvidence()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal var signOut: suspend () -> Unit = { GoogleLinkCoordinator.unlinkAndSignOut() }
    internal var googleLinked: () -> Boolean = { FileApexServices.settings.googleAccountLinkEnabled.value }
    internal var handledVersion: () -> Long = { FileApexServices.settings.kickHandledVersion() }
    internal var markHandled: (Long) -> Unit = { FileApexServices.settings.setKickHandledVersion(it) }
    internal var dropClusterKey: () -> Unit = { ClusterRecordKeys.forget() }
    internal var announce: () -> Unit = { BriefToast.show(AppI18n.t("removed_signed_out_google")) }

    /** [revokedAt] is 0 when the answer carried no stamp (a route that does not read headers): nothing is done then. */
    fun onRevoked(host: String, revokedAt: Long, roster: List<PairedDeviceEntity>, nowMs: Long = TimeUtils.now()) {
        val peer = roster.firstOrNull { !it.isRemoved && it.tlsPin.isNotEmpty() && it.lastKnownIp.trim() == host.trim() } ?: return
        sighting(peer, revokedAt, nowMs)
    }

    /**
     * A pinned peer's removal list names this device. It counts only when its stamp is above this device's own
     * ([selfStamp], raised by a pairing), so an old removal never undoes a re-pair.
     */
    fun onTombstone(peer: PairedDeviceEntity, record: RemovedDeviceRecord, selfStamp: Long, nowMs: Long = TimeUtils.now()) {
        if (record.membershipProtocol < com.fileapex.domain.peer.ClusterClock.MEMBERSHIP_PROTOCOL) return
        if (peer.isRemoved || peer.tlsPin.isEmpty() || record.membershipVersion() <= selfStamp) return
        sighting(peer, record.membershipVersion(), nowMs)
    }

    /**
     * Firebase itself says this device's user is disabled: a roster device removed it through the relay. That needs no
     * second opinion from the peers, so this device signs out of Google inside FileApex at once. Signing in again on
     * purpose is not undone: the sign-in goes through a new enrol, and a disabled user cannot ask a second time.
     */
    fun onFirebaseUserDisabled() {
        println("KickedByPeer: Firebase user is disabled")
        dropClusterKey()
        if (!googleLinked() || !signingOut.compareAndSet(false, true)) return
        scope.launch {
            try {
                cancellableCatching { signOut() }
                    .onSuccess { announce() }
                    .onFailure { println("KickedByPeer: Google sign-out failed - ${it::class.simpleName}") }
            } finally {
                signingOut.set(false)
            }
        }
    }

    private val signingOut = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun sighting(peer: PairedDeviceEntity, revokedAt: Long, nowMs: Long) {
        com.fileapex.cloud.device.DeviceIdentity.onKicked()
        if (revokedAt <= 0L || revokedAt <= handledVersion()) return
        if (!evidence.confirmed(revokedAt, peer.deviceId, nowMs)) return
        markHandled(revokedAt)
        dropClusterKey()
        if (!googleLinked()) return
        scope.launch {
            cancellableCatching { signOut() }
                .onSuccess { announce() }
                .onFailure { println("KickedByPeer: Google sign-out failed - ${it::class.simpleName}") }
        }
    }
}
