package com.omninode.platform

/**
 * Shows a user-visible notification when this device successfully receives file(s).
 * No-op when transfer notifications are disabled in settings.
 */
expect fun notifyFilesReceived(fileNames: List<String>)
