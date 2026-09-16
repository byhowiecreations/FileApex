package com.fileapex.platform

import com.fileapex.domain.clipboard.ClipboardSharePolicy
import java.awt.Toolkit
import java.awt.datatransfer.FlavorListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

actual object ClipboardChangeMonitor {
    /** Same cadence as macOS TrayBridge pasteboard watch (`Timer(timeInterval: 0.45)`). */
    private val WATCH_INTERVAL_MS = ClipboardSharePolicy.DESKTOP_CLIPBOARD_WATCH_MS

    private val listener = AtomicReference<FlavorListener?>(null)
    private val lastSeen = AtomicReference<String?>(null)
    private val callback = AtomicReference<((String) -> Unit)?>(null)
    private val pollTask = AtomicReference<ScheduledFuture<*>?>(null)
    private val pollInFlight = AtomicBoolean(false)
    private val nativeWatch = AtomicBoolean(false)
    private val windowFocused = AtomicBoolean(true)
    private val pollExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "fileapex-clipboard-poll").apply { isDaemon = true }
    }

    actual fun start(onTextChanged: (String) -> Unit) {
        stop()
        callback.set(onTextChanged)
        lastSeen.set(PlatformClipboard.getSystemClipboardText())
        if (DesktopPlatformPaths.isMacOs() && DesktopMacTrayBridge.startClipboardWatch { text ->
            emitText(text)
        }) {
            nativeWatch.set(true)
            println("ClipboardChangeMonitor: AppKit pasteboard watch started")
            return
        }
        val next = FlavorListener {
            emitCurrentIfChanged()
        }
        listener.set(next)
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.addFlavorListener(next)
        }
        pollTask.set(
            pollExecutor.scheduleWithFixedDelay(
                { emitCurrentIfChanged() },
                WATCH_INTERVAL_MS,
                WATCH_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        )
        println("ClipboardChangeMonitor: desktop watch started (${WATCH_INTERVAL_MS}ms, matches Mac)")
    }

    actual fun stop() {
        callback.set(null)
        if (nativeWatch.getAndSet(false)) {
            DesktopMacTrayBridge.stopClipboardWatch()
        }
        pollTask.getAndSet(null)?.cancel(false)
        val previous = listener.getAndSet(null)
        if (previous != null) {
            runCatching {
                Toolkit.getDefaultToolkit().systemClipboard.removeFlavorListener(previous)
            }
        }
        lastSeen.set(null)
    }

    actual fun onAppForegrounded() {
        // Hop off whatever thread called us (often Compose Main / EDT).
        pollExecutor.execute { emitCurrentIfChanged() }
    }

    actual fun onAppBackgrounded() = Unit

    actual fun onWindowFocusChanged(hasFocus: Boolean) {
        windowFocused.set(hasFocus)
        if (hasFocus) pollExecutor.execute { emitCurrentIfChanged() }
    }

    actual fun hasWindowFocus(): Boolean = windowFocused.get()

    actual fun onShizukuOptInChanged() = Unit

    private fun emitCurrentIfChanged() {
        if (!pollInFlight.compareAndSet(false, true)) return
        try {
            val text = PlatformClipboard.getSystemClipboardText()?.takeIf { it.isNotBlank() } ?: return
            emitText(text)
        } finally {
            pollInFlight.set(false)
        }
    }

    private fun emitText(text: String) {
        if (ClipboardShareSuppressor.isApplyingRemote) return
        val previous = lastSeen.getAndSet(text)
        if (previous == text) return
        println("ClipboardChangeMonitor: clipboard changed (${text.length} chars)")
        callback.get()?.invoke(text)
    }
}
