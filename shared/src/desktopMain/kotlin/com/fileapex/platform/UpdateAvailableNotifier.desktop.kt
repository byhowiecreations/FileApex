package com.fileapex.platform

import com.fileapex.update.PendingUpdateOffer

actual fun notifyAppUpdateAvailable(offer: PendingUpdateOffer) {
    println(
        "UpdateAvailableNotifier: FileApex ${offer.remoteVersion} available — " +
            offer.notificationDetail(maxNoteLines = 2)
    )
}

actual fun dismissAppUpdateNotification() = Unit
