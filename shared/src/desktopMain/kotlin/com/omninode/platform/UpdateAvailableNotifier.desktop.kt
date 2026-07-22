package com.omninode.platform

import com.omninode.update.PendingUpdateOffer

actual fun notifyAppUpdateAvailable(offer: PendingUpdateOffer) {
    println(
        "UpdateAvailableNotifier: OmniNode ${offer.remoteVersion} available — " +
            offer.notificationDetail(maxNoteLines = 2)
    )
}

actual fun dismissAppUpdateNotification() = Unit
