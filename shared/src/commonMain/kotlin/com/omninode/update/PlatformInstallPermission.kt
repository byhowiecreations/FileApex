package com.omninode.update

/**
 * Android: opens system “Install unknown apps” settings when the app cannot install APKs.
 * Desktop: no-op.
 */
expect object PlatformInstallPermission {
    /**
     * If this platform requires unknown-app install permission and it is not granted,
     * launch the system settings screen for this package.
     *
     * @return true when installs are already allowed (or not required); false when the
     * settings screen was opened because permission is missing.
     */
    fun ensureCanRequestPackageInstalls(): Boolean
}
