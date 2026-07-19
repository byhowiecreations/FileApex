package com.omninode.platform

/**
 * System notification when a newer OmniNode build is available and install is starting.
 */
expect fun notifyAppUpdateAvailable(versionLabel: String, detail: String?)
