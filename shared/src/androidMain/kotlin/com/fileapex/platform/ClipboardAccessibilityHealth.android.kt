package com.fileapex.platform

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.di.FileApexServices
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

actual object ClipboardAccessibilityHealth {
    private const val TAG = "ClipboardA11yHealth"

    private val _needsReenable = MutableStateFlow(false)
    actual val needsReenable: StateFlow<Boolean> = _needsReenable.asStateFlow()

    private val bound = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val dismissedUnhealthy = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val graceRunnable = Runnable { refresh() }

    @Volatile
    private var graceAnchorEpochMs: Long = TimeUtils.now()

    @Volatile
    private var lastHealthLog: String? = null

    private var stateListener: AccessibilityManager.AccessibilityStateChangeListener? = null
    private var servicesListener: AccessibilityManager.AccessibilityServicesStateChangeListener? = null

    actual fun start() {
        val context = androidAppContextOrNull() ?: return
        if (!started.compareAndSet(false, true)) {
            refresh()
            return
        }
        graceAnchorEpochMs = TimeUtils.now()
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (manager != null) {
            val stateCb = AccessibilityManager.AccessibilityStateChangeListener { refresh() }
            stateListener = stateCb
            manager.addAccessibilityStateChangeListener(stateCb)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val servicesCb = AccessibilityManager.AccessibilityServicesStateChangeListener { refresh() }
                servicesListener = servicesCb
                manager.addAccessibilityServicesStateChangeListener(context.mainExecutor, servicesCb)
            }
        }
        refresh()
        scheduleGraceRefresh()
    }

    actual fun onBound() {
        bound.set(true)
        dismissedUnhealthy.set(false)
        mainHandler.removeCallbacks(graceRunnable)
        refresh()
        Log.i(TAG, "accessibility service bound")
    }

    actual fun onUnbound() {
        bound.set(false)
        graceAnchorEpochMs = TimeUtils.now()
        refresh()
        scheduleGraceRefresh()
        Log.w(TAG, "accessibility service unbound")
    }

    actual fun refresh() {
        if (FileApexServices.isDatabaseReady()) {
            val settings = FileApexServices.settings
            val summary =
                "sharing=${settings.clipboardSharingEnabled.value} setting=${settings.clipboardAccessibilityEnabled.value} listed=${isServiceListedEnabled()} bound=${bound.get()}"
            if (summary != lastHealthLog) {
                lastHealthLog = summary
                Log.i(TAG, "clipboard a11y $summary")
            }
        }
        val unhealthy = evaluateUnhealthy()
        if (!unhealthy) {
            dismissedUnhealthy.set(false)
            _needsReenable.value = false
            return
        }
        _needsReenable.value = !dismissedUnhealthy.get()
        if (_needsReenable.value) {
            Log.w(TAG, "clipboard accessibility needs re-enable (oem drop)")
        }
    }

    actual fun dismissPrompt() {
        dismissedUnhealthy.set(true)
        _needsReenable.value = false
    }

    actual fun openFix() {
        ClipboardAccessibilitySettings.openSystemPrompt()
    }

    actual fun isBound(): Boolean = bound.get()

    actual fun isListed(): Boolean = isServiceListedEnabled()

    private fun evaluateUnhealthy(): Boolean {
        if (!FileApexServices.isDatabaseReady()) return false
        val settings = FileApexServices.settings
        val elapsed = TimeUtils.now() - graceAnchorEpochMs
        return ClipboardAccessibilityHealthPolicy.needsReenablePrompt(
            sharingEnabled = settings.clipboardSharingEnabled.value,
            accessibilitySettingEnabled = settings.clipboardAccessibilityEnabled.value,
            serviceListedEnabled = isServiceListedEnabled(),
            serviceBound = bound.get(),
            elapsedSinceProcessOrUnbindMs = elapsed
        )
    }

    private fun isServiceListedEnabled(): Boolean = ClipboardAccessibilitySettings.isServiceEnabled()

    private fun scheduleGraceRefresh() {
        mainHandler.removeCallbacks(graceRunnable)
        mainHandler.postDelayed(graceRunnable, ClipboardAccessibilityHealthPolicy.BIND_GRACE_MS)
    }
}
