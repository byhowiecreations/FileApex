package com.fileapex.domain.presence

import com.fileapex.di.FileApexServices

/** App lifecycle hook — debounced foreground peer refresh (no idle background polling). */
object PresenceForegroundRefresh {
    fun onAppForegrounded() {
        if (!FileApexServices.isDatabaseReady()) return
        FileApexServices.presenceMonitor.refreshPeersOnForeground()
    }
}
