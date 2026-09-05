package com.fileapex.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.fileapex.di.FileApexServices
import com.fileapex.domain.clipboard.ClipboardCopySignals
import com.fileapex.domain.clipboard.ClipboardShareCoordinator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ClipboardAccessibilityService : AccessibilityService() {
    private val lastSelectedText = AtomicReference<String?>(null)
    private val lastFocusedText = AtomicReference<String?>(null)
    private val lastEditorText = AtomicReference<String?>(null)
    private val lastEditorPackage = AtomicReference<String?>(null)
    private val pendingClipRetry = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = CAPTURE_EVENT_TYPES
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            // 0 = no coalescing. 100ms was dropping copy clicks next to selection events.
            notificationTimeout = 0
        }
        FileApexAndroidBootstrap.ensureInitialized(this)
        ClipboardAccessibilityHealth.onBound()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (ClipboardShareSuppressor.isApplyingRemote) return@OnPrimaryClipChangedListener
            if (publishClipboardPayload()) return@OnPrimaryClipChangedListener
            // API 34+/35: listener still fires, primaryClip is redacted without window focus.
            scheduleClipRetries()
        }
        clipListener = listener
        clipboard.addPrimaryClipChangedListener(listener)
        Log.i(TAG, "clipboard a11y connected sdk=${Build.VERSION.SDK_INT}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!isCaptureEnabled()) {
            FileApexAndroidBootstrap.ensureInitialized(this)
            if (!isCaptureEnabled()) return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> {
                if (isCopyAction(event)) onCopyActionDetected(event.packageName?.toString())
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> rememberSelectedRange(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> rememberEditorText(event)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> rememberFocusedNode(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (
                    ClipboardCopySignals.isOemClipboardOverlay(
                        event.packageName?.toString(),
                        event.className?.toString()
                    )
                ) {
                    onCopyActionDetected(event.packageName?.toString())
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ClipboardAccessibilityHealth.onUnbound()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        pendingClipRetry.set(false)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipListener?.let { listener ->
            runCatching { clipboard?.removePrimaryClipChangedListener(listener) }
        }
        clipListener = null
        ClipboardAccessibilityHealth.onUnbound()
        super.onDestroy()
    }

    private fun onCopyActionDetected(sourcePackage: String?) {
        if (publishClipboardPayload(sourcePackage)) return
        scheduleClipRetries(sourcePackage)
    }

    private fun scheduleClipRetries(sourcePackage: String? = null) {
        if (!pendingClipRetry.compareAndSet(false, true)) return
        CLIP_READ_RETRY_MS.forEach { delayMs ->
            mainHandler.postDelayed({
                if (publishClipboardPayload(sourcePackage)) {
                    mainHandler.removeCallbacksAndMessages(null)
                    pendingClipRetry.set(false)
                }
            }, delayMs)
        }
        mainHandler.postDelayed({
            pendingClipRetry.set(false)
        }, CLIP_READ_RETRY_MS.last() + 50L)
    }

    private fun publishClipboardPayload(sourcePackage: String? = null): Boolean {
        if (!isCaptureEnabled()) return false
        if (ClipboardShareSuppressor.isApplyingRemote) return false
        if (!FileApexAndroidBootstrap.ensureInitialized(this)) return false
        val text = resolvePayloadText(sourcePackage) ?: return false
        ClipboardShareCoordinator.onLocalClipboardChanged(text)
        return true
    }

    private fun resolvePayloadText(sourcePackage: String?): String? {
        val nodeCached = firstUsableNodeText(sourcePackage)
        val windowFocused = ClipboardChangeMonitor.hasWindowFocus()
        if (ClipboardCopySignals.preferCachedNodeOverClipboard(windowFocused)) {
            nodeCached?.let { return it }
            ClipboardCopySignals.usableText(ClipboardShizukuAccess.tryReadText())?.let { return it }
            ClipboardCopySignals.usableText(readServiceClipboardText())?.let { return it }
            return null
        }
        ClipboardCopySignals.usableText(readServiceClipboardText())?.let { return it }
        nodeCached?.let { return it }
        ClipboardCopySignals.usableText(ClipboardShizukuAccess.tryReadText())?.let { return it }
        return null
    }

    private fun firstUsableNodeText(sourcePackage: String?): String? {
        ClipboardCopySignals.usableText(lastSelectedText.get())?.let { return it }
        ClipboardCopySignals.usableText(extractSelectedTextFromActiveWindow())?.let { return it }
        val samePackage = sourcePackage.isNullOrBlank() || sourcePackage == lastEditorPackage.get()
        if (samePackage) {
            ClipboardCopySignals.usableText(lastFocusedText.get())?.let { return it }
            ClipboardCopySignals.usableText(lastEditorText.get())?.let { return it }
        }
        return null
    }

    private fun readServiceClipboardText(): String? {
        return PlatformClipboard.readClipboardText(this)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extractSelectedTextFromActiveWindow(): String? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        val visited = AtomicInteger(0)
        return try {
            findSelectedText(root, depth = 0, visited = visited)
                ?.trim()
                ?.takeIf { ClipboardCopySignals.isUsablePayload(it) }
        } finally {
            recycleNode(root)
        }
    }

    private fun findSelectedText(
        node: AccessibilityNodeInfo,
        depth: Int,
        visited: AtomicInteger
    ): String? {
        if (depth > MAX_TREE_DEPTH || visited.incrementAndGet() > MAX_TREE_NODES) return null
        val text = node.text?.toString().orEmpty()
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start >= 0 && end > start && end <= text.length) {
            val slice = ClipboardCopySignals.selectedSlice(text, start, end)
            if (ClipboardCopySignals.isUsablePayload(slice)) return slice
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = try {
                findSelectedText(child, depth + 1, visited)
            } finally {
                recycleNode(child)
            }
            if (found != null) return found
        }
        return null
    }

    private fun isCopyAction(event: AccessibilityEvent): Boolean {
        if (ClipboardCopySignals.isCopyLabel(event.contentDescription?.toString()) ||
            labelsLookLikeCopy(event.text)
        ) {
            return true
        }
        val source = event.source ?: return false
        return try {
            nodeLooksLikeCopy(source) || source.parent?.let { parent ->
                try {
                    nodeLooksLikeCopy(parent)
                } finally {
                    recycleNode(parent)
                }
            } == true
        } finally {
            recycleNode(source)
        }
    }

    private fun nodeLooksLikeCopy(node: AccessibilityNodeInfo): Boolean {
        return ClipboardCopySignals.isCopyLabel(node.text?.toString()) ||
            ClipboardCopySignals.isCopyLabel(node.contentDescription?.toString()) ||
            ClipboardCopySignals.isCopyViewId(node.viewIdResourceName)
    }

    private fun labelsLookLikeCopy(labels: List<CharSequence>?): Boolean {
        if (labels.isNullOrEmpty()) return false
        return labels.any { ClipboardCopySignals.isCopyLabel(it.toString()) }
    }

    private fun rememberSelectedRange(event: AccessibilityEvent) {
        rememberIfPayload(nodeEventText(event))
    }

    private fun rememberEditorText(event: AccessibilityEvent) {
        val text = nodeEventText(event) ?: return
        lastEditorText.set(text)
        lastEditorPackage.set(event.packageName?.toString())
    }

    private fun rememberFocusedNode(event: AccessibilityEvent) {
        val text = nodeEventText(event) ?: return
        lastFocusedText.set(text)
        lastEditorPackage.set(event.packageName?.toString())
        ClipboardCopySignals.usableText(text)?.let { lastEditorText.set(it) }
    }

    private fun nodeEventText(event: AccessibilityEvent): String? {
        val source = event.source
        return try {
            ClipboardCopySignals.textFromNodeEvent(
                eventTexts = event.text?.map { it.toString() },
                sourceText = source?.text?.toString(),
                fromIndex = event.fromIndex,
                toIndex = event.toIndex,
                selectionStart = source?.textSelectionStart ?: -1,
                selectionEnd = source?.textSelectionEnd ?: -1
            )
        } finally {
            if (source != null) recycleNode(source)
        }
    }

    private fun rememberIfPayload(text: String?) {
        if (!ClipboardCopySignals.isUsablePayload(text)) return
        lastSelectedText.set(text)
    }

    private fun isCaptureEnabled(): Boolean {
        if (!FileApexServices.isDatabaseReady()) return false
        val settings = FileApexServices.settings
        return settings.clipboardSharingEnabled.value && settings.clipboardAccessibilityEnabled.value
    }

    private fun recycleNode(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val method = node.javaClass.getMethod("recycle")
                method.invoke(node)
            }
        }
    }

    private companion object {
        const val TAG = "ClipboardA11y"
        const val MAX_TREE_DEPTH = 12
        const val MAX_TREE_NODES = 80
        val CLIP_READ_RETRY_MS = longArrayOf(80L, 250L, 600L)
        const val CAPTURE_EVENT_TYPES =
            AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
    }
}
