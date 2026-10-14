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
    // False until a window or Activity reports it is in front: a process started by boot, FCM or the
    // watchdog has no UI and must use the standby cadence.
    private val _isForeground = MutableStateFlow(false)

    /** Changes each time the app returns to the front, so screens can refetch what they show. */
    val foregroundedAtMs: StateFlow<Long> = _foregroundedAtMs.asStateFlow()
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    fun onAppForegrounded() {
        _isForeground.value = true
        runCatching { com.fileapex.network.FileApexMdnsBrowser.requestProbe() }
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
        _isForeground.value = false
        if (!FileApexServices.isDatabaseReady()) return
        FileApexServices.presenceMonitor.setAppInForeground(false)
        com.fileapex.domain.clipboard.ClipboardShareCoordinator.onAppBackgrounded()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!FileApexServices.isDatabaseReady()) return
        com.fileapex.domain.clipboard.ClipboardShareCoordinator.onWindowFocusChanged(hasFocus)
    }
}
