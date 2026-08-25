package com.fileapex.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object ClipboardAccessibilityHealth {
    actual val needsReenable: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    actual fun start() = Unit

    actual fun onBound() = Unit

    actual fun onUnbound() = Unit

    actual fun refresh() = Unit

    actual fun dismissPrompt() = Unit

    actual fun openFix() = Unit

    actual fun isBound(): Boolean = false

    actual fun isListed(): Boolean = false
}
