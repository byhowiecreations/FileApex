package com.fileapex.domain.link

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.util.cancellableCatching
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Phone side of the link: the computer this phone shows on Simple Home. When the user switches computer the
 * phone tells the one it left and the one it chose at once, so the two know the link is exclusive.
 */
object PhoneLink {
    private class Shown(val id: String, val chosenAtEpochMs: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shown = AtomicReference(Shown("", 0L))

    /** Id of the computer shown, or empty when Simple Home is not on screen. */
    fun shownId(): String = shown.get().id

    /** Sets the computer shown (empty to pause) and tells the computers that lost or gained the link. */
    fun setShown(computerId: String, chosenAtEpochMs: Long) {
        val next = Shown(computerId.trim(), if (computerId.isBlank()) 0L else chosenAtEpochMs)
        val previous = shown.getAndSet(next)
        if (previous.id == next.id && previous.chosenAtEpochMs == next.chosenAtEpochMs) return
        val calls = announcements(previous.id, next.id, next.chosenAtEpochMs)
        if (calls.isEmpty()) return
        scope.launch { calls.forEach { (peerId, signal) -> announce(peerId, signal) } }
    }

    /** Who to tell about a change: the old partner always, the new one only when this phone's user chose it. */
    internal fun announcements(previousId: String, nextId: String, nextChosenAt: Long): List<Pair<String, HealthSignal>> {
        if (previousId == nextId) return emptyList()
        val calls = ArrayList<Pair<String, HealthSignal>>()
        if (previousId.isNotEmpty()) {
            // A switch carries its time so the old partner can tell it from a pause such as leaving the screen.
            val releasedAt = if (nextId.isNotEmpty() && nextChosenAt > 0L) nextChosenAt else 0L
            calls += previousId to HealthSignal(HealthSignal.Kind.Link, PairLink.Signal(false, releasedAt))
        }
        if (nextId.isNotEmpty() && nextChosenAt > 0L) {
            calls += nextId to HealthSignal(HealthSignal.Kind.Link, PairLink.Signal(true, nextChosenAt))
        }
        return calls
    }

    /** What a presence sweep sends to [peerId]: a renewal for the computer this phone's user chose, nothing otherwise. */
    internal fun sweepSignal(peerId: String, shownId: String, chosenAt: Long): HealthSignal? =
        if (peerId.isNotEmpty() && peerId == shownId && chosenAt > 0L) {
            HealthSignal(HealthSignal.Kind.Link, PairLink.Signal(true, chosenAt))
        } else {
            null
        }

    /** What this phone's saved choice says to a computer it did not choose: a release at the time of the choice, so a lost one heals. */
    internal fun releaseSignal(peerId: String, choiceId: String, choiceAt: Long): HealthSignal? =
        PairLinkState.releaseTimeFor(peerId, choiceId, choiceAt).takeIf { it > 0L }
            ?.let { HealthSignal(HealthSignal.Kind.Link, PairLink.Signal(false, it)) }

    /** The link signal to add to the sweep poll of [peer], or null when it gets none. */
    fun pollSignalFor(peer: PairedDeviceEntity): HealthSignal? {
        if (!PeerPlatform.isDesktop(peer.os, peer.platform) || peer.tlsPin.isEmpty()) return null
        val current = shown.get()
        val settings = FileApexServices.settings
        return sweepSignal(peer.deviceId, current.id, current.chosenAtEpochMs)
            ?: releaseSignal(peer.deviceId, settings.simpleActiveDeviceId.value, settings.pairLink.chosenAt.value)
    }

    private suspend fun announce(peerId: String, signal: HealthSignal) {
        val device = FileApexServices.deviceRepository.getDevice(peerId) ?: return
        if (device.isRemoved || device.tlsPin.isEmpty() || !PeerPlatform.isDesktop(device.os, device.platform)) return
        val endpoint = FileApexServices.presenceMonitor.endpointIfOnline(device) ?: return
        cancellableCatching("PhoneLink: FileApexServices.client.pingHealth") { FileApexServices.client.pingHealth(endpoint.host, endpoint.port, signal = signal) }
    }
}
