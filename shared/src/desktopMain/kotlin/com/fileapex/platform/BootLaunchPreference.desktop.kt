package com.fileapex.platform

import com.fileapex.di.FileApexServices

/** Windows and Linux start the app at sign-in (DesktopAutostart); the Mac has no such setting yet. */
actual object BootLaunchPreference {
    actual fun usesDesktopAutostart(): Boolean =
        DesktopPlatformPaths.desktopOs == DesktopPlatformPaths.DesktopOs.Windows ||
            DesktopPlatformPaths.desktopOs == DesktopPlatformPaths.DesktopOs.Linux

    actual fun onPreferenceChanged(enabled: Boolean) {
        if (!usesDesktopAutostart()) return
        if (!DesktopAutostart.apply(enabled)) println("BootLaunchPreference: sign-in start could not be updated")
    }

    /** Re-applies the saved choice at launch, so a moved install or AppImage keeps a working entry. */
    actual fun syncFromSettings() {
        if (!usesDesktopAutostart()) return
        onPreferenceChanged(FileApexServices.settings.desktopAutostart.value)
    }
}
