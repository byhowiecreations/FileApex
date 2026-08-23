package com.fileapex.domain.clipboard

import com.fileapex.util.sha256Hex

object ClipboardPushDeduper {
    @Volatile
    var isInitializing: Boolean = true
        private set

    @Volatile
    private var lastHash: String? = null

    fun beginInitialization() {
        isInitializing = true
    }

    fun endInitialization() {
        isInitializing = false
    }

    fun hashOf(text: String): String = sha256Hex(text.trim().encodeToByteArray())

    fun remember(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        lastHash = hashOf(trimmed)
    }

    fun isDuplicate(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val previous = lastHash ?: return false
        return previous == hashOf(trimmed)
    }

    fun shouldAllowAutomaticPush(text: String): Boolean {
        if (isInitializing) return false
        return !isDuplicate(text)
    }

    fun shouldAllowManualPush(text: String): Boolean = !isDuplicate(text)

    fun clearSession() {
        lastHash = null
        isInitializing = false
    }
}
