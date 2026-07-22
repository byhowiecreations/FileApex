package com.omninode.platform

import com.omninode.update.PendingUpdateOffer

/**
 * System notification when a newer OmniNode build is available.
 */
expect fun notifyAppUpdateAvailable(offer: PendingUpdateOffer)

/** Clears the app-update notification after the user acts or dismisses the sheet. */
expect fun dismissAppUpdateNotification()
