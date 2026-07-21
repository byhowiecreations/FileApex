package com.omninode.platform

actual object BriefToast {
    actual fun show(message: String) {
        if (DesktopMacTrayBridge.isLoaded) {
            DesktopMacTrayBridge.showToast(message)
        } else {
            println("BriefToast: $message")
        }
    }
}
