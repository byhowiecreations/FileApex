package com.fileapex.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.fileapex.di.FileApexServices
import com.fileapex.domain.clipboard.ClipboardShareCoordinator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ClipboardAccessibilityService : AccessibilityService() {
    private val lastSelectedText = AtomicReference<String?>(null)
    private val pendingClipRetry = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        FileApexAndroidBootstrap.ensureInitialized(this)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            publishClipboardPayload()
        }
        clipListener = listener
        clipboard.addPrimaryClipChangedListener(listener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isCaptureEnabled()) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (isCopyAction(event)) onCopyActionDetected()
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> rememberSelectedRange(event)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        pendingClipRetry.set(false)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipListener?.let { listener ->
            runCatching { clipboard?.removePrimaryClipChangedListener(listener) }
        }
        clipListener = null
        super.onDestroy()
    }

    private fun onCopyActionDetected() {
        if (publishClipboardPayload()) return
        if (!pendingClipRetry.compareAndSet(false, true)) return
        mainHandler.postDelayed({
            pendingClipRetry.set(false)
            if (publishClipboardPayload()) return@postDelayed
            val selected = lastSelectedText.get().orEmpty()
            if (selected.isNotBlank() && !isCopyStatusOnly(selected)) {
                ClipboardShareCoordinator.onLocalClipboardChanged(selected)
            }
        }, CLIP_READ_RETRY_MS)
    }

    private fun publishClipboardPayload(): Boolean {
        if (!isCaptureEnabled()) return false
        if (!FileApexAndroidBootstrap.ensureInitialized(this)) return false
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return false
        if (clip.itemCount <= 0) return false
        val text = PlatformClipboard.readClipboardText(this)?.trim().orEmpty()
        if (text.isBlank() || isCopyStatusOnly(text)) return false
        ClipboardShareCoordinator.onLocalClipboardChanged(text)
        return true
    }

    private fun isCopyAction(event: AccessibilityEvent): Boolean {
        if (labelsLookLikeCopy(event.text) || looksLikeCopyLabel(event.contentDescription?.toString())) {
            return true
        }
        val source = event.source ?: return false
        if (nodeLooksLikeCopy(source)) return true
        val parent = source.parent
        return parent != null && nodeLooksLikeCopy(parent)
    }

    private fun nodeLooksLikeCopy(node: AccessibilityNodeInfo): Boolean {
        return looksLikeCopyLabel(node.text?.toString()) ||
            looksLikeCopyLabel(node.contentDescription?.toString()) ||
            looksLikeCopyViewId(node.viewIdResourceName)
    }

    private fun labelsLookLikeCopy(labels: List<CharSequence>?): Boolean {
        if (labels.isNullOrEmpty()) return false
        return labels.any { looksLikeCopyLabel(it.toString()) }
    }

    private fun looksLikeCopyLabel(raw: String?): Boolean {
        val normalized = raw?.trim()?.lowercase()?.replace('\u00a0', ' ').orEmpty()
        if (normalized.isEmpty() || normalized.contains("copyright")) return false
        if (COPY_LABELS.contains(normalized)) return true
        return normalized.startsWith("copy ") || normalized.startsWith("cut ")
    }

    private fun looksLikeCopyViewId(viewId: String?): Boolean {
        if (viewId.isNullOrBlank()) return false
        val name = viewId.substringAfter('/').lowercase()
        if (name.contains("copyright")) return false
        return name == "copy" ||
            name == "cut" ||
            name == "action_copy" ||
            name == "menu_copy" ||
            name == "copy_button" ||
            name.endsWith("_copy")
    }

    private fun rememberSelectedRange(event: AccessibilityEvent) {
        val full = event.text?.joinToString("").orEmpty()
        rememberIfPayload(selectedSlice(full, event.fromIndex, event.toIndex))
    }

    private fun selectedSlice(full: String, start: Int, end: Int): String {
        if (full.isEmpty() || start < 0 || end <= start || end > full.length) return ""
        if (start == 0 && end == full.length) return ""
        return full.substring(start, end).trim()
    }

    private fun rememberIfPayload(text: String) {
        if (text.isBlank() || isCopyStatusOnly(text) || looksLikeCopyLabel(text)) return
        lastSelectedText.set(text)
    }

    private fun isCaptureEnabled(): Boolean {
        if (!FileApexServices.isDatabaseReady()) return false
        val settings = FileApexServices.settings
        return settings.clipboardSharingEnabled.value && settings.clipboardAccessibilityEnabled.value
    }

    private fun isCopyStatusOnly(text: String): Boolean {
        val lower = text.lowercase()
        return lower == "copied" ||
            lower == "copied to clipboard" ||
            lower.startsWith("copied to clipboard")
    }

    private companion object {
        const val CLIP_READ_RETRY_MS = 200L
        val COPY_LABELS = setOf(
            "copy",
            "cut",
            "copy link",
            "copy text",
            "copy url",
            "copy address",
            "copy to clipboard"
        )
    }
}
