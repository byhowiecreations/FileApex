package com.fileapex.platform

import com.fileapex.update.PendingUpdateOffer

/**
 * System notification when a newer FileApex build is available.
 */
expect fun notifyAppUpdateAvailable(offer: PendingUpdateOffer)

/** Clears the app-update notification after the user acts or dismisses the sheet. */
expect fun dismissAppUpdateNotification()
