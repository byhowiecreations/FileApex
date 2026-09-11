package com.fileapex.platform

import com.fileapex.di.FileApexServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object DesktopClipboardOptInState {
    private val _pendingSender = MutableStateFlow<String?>(null)
    val pendingSender = _pendingSender.asStateFlow()

    fun requestOptIn(senderDeviceName: String) {
        _pendingSender.value = senderDeviceName
    }

    fun consume() {
        _pendingSender.value = null
    }
}

actual fun notifyClipboardOptInRequested(senderDeviceName: String) {
    if (FileApexServices.settings.clipboardOptInPromptShown.value) return
    if (FileApexServices.settings.clipboardSharingEnabled.value) return
    println("ClipboardOptInNotifier (Desktop): Opt-in requested from $senderDeviceName")
    DesktopClipboardOptInState.requestOptIn(senderDeviceName)
}
