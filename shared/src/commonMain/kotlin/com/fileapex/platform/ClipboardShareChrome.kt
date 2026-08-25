package com.fileapex.platform

object ClipboardShareChrome {
    @Volatile
    var listener: (() -> Unit)? = null

    fun fire() {
        listener?.invoke()
    }
}
