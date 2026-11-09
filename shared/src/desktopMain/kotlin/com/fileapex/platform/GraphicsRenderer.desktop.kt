package com.fileapex.platform

actual fun graphicsRendererChoice(): String =
    if (DesktopPlatformPaths.isWindows()) {
        WindowsGraphicsMode.load(DesktopPlatformPaths.applicationSupportDirectory()).storageName
    } else {
        WindowsGraphicsMode.Automatic.storageName
    }

actual fun setGraphicsRendererChoice(choice: String) {
    if (!DesktopPlatformPaths.isWindows()) return
    WindowsGraphicsMode.save(DesktopPlatformPaths.applicationSupportDirectory(), WindowsGraphicsMode.fromText(choice))
}
