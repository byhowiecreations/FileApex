package com.fileapex.platform

import kotlinx.coroutines.flow.StateFlow

expect object ClipboardAccessibilityHealth {
    val needsReenable: StateFlow<Boolean>

    fun start()

    fun onBound()

    fun onUnbound()

    fun refresh()

    fun dismissPrompt()

    fun openFix()

    fun isBound(): Boolean

    fun isListed(): Boolean
}
