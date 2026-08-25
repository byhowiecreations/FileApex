package com.fileapex.platform

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.domain.clipboard.ClipboardCopySignals
import com.fileapex.domain.clipboard.ClipboardSharePolicy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

actual object ClipboardChangeMonitor {
    private const val TAG = "ClipboardMonitor"

    private val callback = AtomicReference<((String) -> Unit)?>(null)
    private val lastSeen = AtomicReference<String?>(null)
    private val listening = AtomicBoolean(false)
    private val polling = AtomicBoolean(false)
    private val windowFocused = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private val pollRunnable = object : Runnable {
        override fun run() {
            emitCurrentIfChanged()
            if (polling.get()) {
                mainHandler.postDelayed(
                    this,
                    ClipboardSharePolicy.ANDROID_FOREGROUND_CLIP_POLL_MS
                )
            }
        }
    }

    actual fun start(onTextChanged: (String) -> Unit) {
        callback.set(onTextChanged)
        lastSeen.set(null)
        if (windowFocused.get() || ClipboardShizukuAccess.shouldUse()) {
            ensureListener()
        }
        if (windowFocused.get()) {
            startPoll()
        }
    }

    actual fun stop() {
        callback.set(null)
        lastSeen.set(null)
        stopPoll()
        removeListener()
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun onShizukuReady() {
        onShizukuOptInChanged()
    }

    actual fun onShizukuOptInChanged() {
        if (callback.get() == null) return
        if (windowFocused.get() || ClipboardShizukuAccess.shouldUse()) {
            ensureListener()
            return
        }
        removeListener()
    }

    actual fun onAppForegrounded() {
        ensureListener()
    }

    actual fun onAppBackgrounded() {
        windowFocused.set(false)
        stopPoll()
        mainHandler.removeCallbacksAndMessages(null)
        if (ClipboardShizukuAccess.shouldUse()) {
            ensureListener()
            return
        }
        removeListener()
    }

    actual fun hasWindowFocus(): Boolean = windowFocused.get()

    actual fun onWindowFocusChanged(hasFocus: Boolean) {
        windowFocused.set(hasFocus)
        if (!hasFocus) {
            stopPoll()
            if (ClipboardShizukuAccess.shouldUse()) {
                ensureListener()
            }
            return
        }
        ensureListener()
        startPoll()
        ClipboardSharePolicy.ANDROID_FOCUS_CLIP_RETRY_MS.forEach { delayMs ->
            mainHandler.postDelayed({ emitCurrentIfChanged() }, delayMs)
        }
    }

    private fun ensureListener() {
        if (callback.get() == null) return
        if (!listening.compareAndSet(false, true)) return
        val context = androidAppContextOrNull() ?: run {
            listening.set(false)
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: run {
            listening.set(false)
            return
        }
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (ClipboardShareSuppressor.isApplyingRemote) return@OnPrimaryClipChangedListener
            emitCurrentIfChanged()
            ClipboardSharePolicy.ANDROID_FOCUS_CLIP_RETRY_MS.forEach { delayMs ->
                mainHandler.postDelayed({ emitCurrentIfChanged() }, delayMs)
            }
        }
        clipListener = listener
        runCatching { clipboard.addPrimaryClipChangedListener(listener) }
            .onFailure {
                listening.set(false)
                clipListener = null
            }
            .onSuccess { Log.i(TAG, "clip listener registered shizuku=${ClipboardShizukuAccess.shouldUse()}") }
    }

    private fun startPoll() {
        if (!polling.compareAndSet(false, true)) return
        mainHandler.post(pollRunnable)
    }

    private fun stopPoll() {
        polling.set(false)
        mainHandler.removeCallbacks(pollRunnable)
    }

    private fun removeListener() {
        if (!listening.getAndSet(false)) return
        val context = androidAppContextOrNull()
        val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val listener = clipListener
        clipListener = null
        if (listener != null) {
            runCatching { clipboard?.removePrimaryClipChangedListener(listener) }
        }
    }

    private fun emitCurrentIfChanged() {
        if (ClipboardShareSuppressor.isApplyingRemote) return
        val text = ClipboardCopySignals.usableText(PlatformClipboard.getSystemClipboardText()) ?: return
        val previous = lastSeen.getAndSet(text)
        if (previous == text) return
        Log.i(TAG, "clipboard readable (${text.length} chars)")
        callback.get()?.invoke(text)
    }
}
