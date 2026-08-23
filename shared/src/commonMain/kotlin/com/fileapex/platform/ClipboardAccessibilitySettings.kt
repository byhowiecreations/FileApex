package com.fileapex.platform

expect object ClipboardAccessibilitySettings {
    fun openSystemPrompt()
    fun openAppInfo()
    fun isServiceEnabled(): Boolean
    fun isRestrictedSettingsBlocked(): Boolean
}
