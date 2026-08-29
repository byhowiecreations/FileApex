package com.fileapex.domain.presence

import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.di.FileApexServices

/** App lifecycle hook — debounced foreground peer refresh (no idle background polling). */
object PresenceForegroundRefresh {
    fun onAppForegrounded() {
        if (!FileApexServices.isDatabaseReady()) return
        FileApexServices.presenceMonitor.setAppInForeground(true)
        GoogleLinkCoordinator.refreshCloudRegistry()
        FileApexServices.presenceMonitor.refreshPeersOnForeground()
        FileApexServices.transferQueue.scheduleDrain()
        com.fileapex.domain.clipboard.ClipboardShareCoordinator.onAppForegrounded()
        com.fileapex.platform.ClipboardAccessibilityHealth.refresh()
    }

    fun onAppBackgrounded() {
        if (!FileApexServices.isDatabaseReady()) return
        FileApexServices.presenceMonitor.setAppInForeground(false)
        com.fileapex.domain.clipboard.ClipboardShareCoordinator.onAppBackgrounded()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!FileApexServices.isDatabaseReady()) return
        com.fileapex.domain.clipboard.ClipboardShareCoordinator.onWindowFocusChanged(hasFocus)
    }
}
