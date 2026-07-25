package com.fileapex.platform

actual object BriefToast {
    actual fun show(message: String) {
        when {
            DesktopMacTrayBridge.isLoaded -> DesktopMacTrayBridge.showToast(message)
            DesktopPlatformPaths.isWindows() && DesktopAwtTrayCoordinator.isInstalled() ->
                DesktopAwtTrayCoordinator.showBalloon(message)
            else -> println("BriefToast: $message")
        }
    }
}
