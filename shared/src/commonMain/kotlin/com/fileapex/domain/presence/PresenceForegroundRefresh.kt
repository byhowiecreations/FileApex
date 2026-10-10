package com.fileapex.domain.presence

import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.di.FileApexServices
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App lifecycle hook — debounced foreground peer refresh (no idle background polling). */
object PresenceForegroundRefresh {
    private val _foregroundedAtMs = MutableStateFlow(0L)

    /** Changes each time the app returns to the front, so screens can refetch what they show. */
    val foregroundedAtMs: StateFlow<Long> = _foregroundedAtMs.asStateFlow()

    fun onAppForegrounded() {
        if (!FileApexServices.isDatabaseReady()) return
        _foregroundedAtMs.value = TimeUtils.now()
        FileApexServices.presenceMonitor.setAppInForeground(true)
        FileApexServices.presenceMonitor.refreshPeersOnForeground()
        FileApexServices.transferQueue.scheduleDrain()
        com.fileapex.domain.clipboard.ClipboardShareCoordinator.onAppForegrounded()
        com.fileapex.platform.ClipboardAccessibilityHealth.refresh()
        // Cloud registry after local UI path — Firestore upserts were stacking Room writers.
        GoogleLinkCoordinator.refreshCloudRegistry()
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
