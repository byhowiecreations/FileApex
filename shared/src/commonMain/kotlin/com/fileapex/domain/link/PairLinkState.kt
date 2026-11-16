package com.fileapex.domain.link

import com.fileapex.domain.notifications.NotificationCompanion

/** The device this node shows on Simple Home right now, whichever kind of node it is; empty off Simple Home. */
object PairLinkState {
    fun partnerId(): String = NotificationCompanion.desiredDeviceId().ifEmpty { PhoneLink.shownId() }

    /**
     * What this device's own saved choice says to [peerId] on every check-in: the time of the choice when it is for
     * someone else (a release, so a lost one heals), 0 otherwise. Each device only ever states its own choice to the
     * peer it is talking to; nothing about a third device is passed on.
     */
    fun releaseTimeFor(peerId: String, choiceId: String, choiceAt: Long): Long =
        if (choiceId.isNotEmpty() && choiceId != peerId && choiceAt > 0L) choiceAt else 0L

    /** [releaseTimeFor] with the choice this device's user saved with Switch device. */
    fun releaseTimeFor(peerId: String): Long {
        val settings = com.fileapex.di.FileApexServices.settings
        return releaseTimeFor(peerId, settings.simpleActiveDeviceId.value, settings.pairLink.chosenAt.value)
    }

    /** The shown partner's regular check-in carries the link, so a sweep must send it and never skip that peer. */
    fun carriesLink(peerId: String, shownId: String = partnerId()): Boolean = shownId.isNotEmpty() && peerId == shownId
}
