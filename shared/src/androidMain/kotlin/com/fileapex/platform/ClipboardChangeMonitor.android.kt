package com.fileapex.platform

actual object ClipboardChangeMonitor {
    actual fun start(onTextChanged: (String) -> Unit) = Unit

    actual fun stop() = Unit

    actual fun onAppForegrounded() = Unit

    actual fun onAppBackgrounded() = Unit
}
