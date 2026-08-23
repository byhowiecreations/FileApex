package com.fileapex.platform

actual object ClipboardAccessibilitySettings {
    actual fun openSystemPrompt() = Unit

    actual fun openAppInfo() = Unit

    actual fun isServiceEnabled(): Boolean = false

    actual fun isRestrictedSettingsBlocked(): Boolean = false
}
