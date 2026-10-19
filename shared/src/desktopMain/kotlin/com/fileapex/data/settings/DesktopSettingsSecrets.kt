package com.fileapex.data.settings

import com.fileapex.platform.DesktopPlatformPaths
import com.fileapex.security.FileKeyVault
import com.fileapex.security.LinuxSecretVault
import com.fileapex.security.VaultSealer
import com.fileapex.security.tls.MacKeychainTlsVault
import com.fileapex.security.tls.WindowsDpapiTlsVault
import java.io.File

/** The platform's own secret store wraps the settings secrets: Keychain, DPAPI, or the desktop keyring with a file-key fallback. */
object DesktopSettingsSecrets {
    fun sealer(): SecretSealer = when (DesktopPlatformPaths.desktopOs) {
        DesktopPlatformPaths.DesktopOs.MacOs -> VaultSealer(MacKeychainTlsVault(service = "com.fileapex.settings"))
        DesktopPlatformPaths.DesktopOs.Windows -> VaultSealer(WindowsDpapiTlsVault())
        DesktopPlatformPaths.DesktopOs.Linux,
        DesktopPlatformPaths.DesktopOs.Other ->
            VaultSealer(LinuxSecretVault(FileKeyVault(File(DesktopPlatformPaths.applicationSupportDirectory(), "secrets"))))
    }
}
