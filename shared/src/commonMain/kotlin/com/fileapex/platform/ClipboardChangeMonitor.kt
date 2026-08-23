package com.fileapex.platform

expect object ClipboardChangeMonitor {
    fun start(onTextChanged: (String) -> Unit)
    fun stop()
    fun onAppForegrounded()
    fun onAppBackgrounded()
}
