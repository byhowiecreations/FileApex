package com.fileapex.platform

object ClipboardShizukuPolicy {
    enum class ToggleHint {
        SUBTITLE,
        USING,
        CONNECTED_UNUSED,
        START,
        AUTHORIZE
    }

    fun binderReady(pingBinder: Boolean, permissionGranted: Boolean): Boolean {
        return pingBinder && permissionGranted
    }

    fun shouldUsePrivilegedClipboard(
        optedIn: Boolean,
        pingBinder: Boolean,
        permissionGranted: Boolean
    ): Boolean = optedIn && binderReady(pingBinder, permissionGranted)

    fun toggleHint(
        optedIn: Boolean,
        installed: Boolean,
        running: Boolean,
        active: Boolean
    ): ToggleHint {
        if (optedIn && active) return ToggleHint.USING
        if (optedIn && running) return ToggleHint.AUTHORIZE
        if (optedIn && installed) return ToggleHint.START
        if (!optedIn && active) return ToggleHint.CONNECTED_UNUSED
        return ToggleHint.SUBTITLE
    }

    fun diagnosticsStatus(
        optedIn: Boolean,
        active: Boolean
    ): ClipboardCheckStatus {
        if (!optedIn) return ClipboardCheckStatus.NOT_REQUIRED
        return if (active) ClipboardCheckStatus.GRANTED else ClipboardCheckStatus.MISSING
    }
}
