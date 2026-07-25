package com.fileapex.platform

/**
 * Android waits for an in-app sheet / notification actions before downloading.
 * Desktop keeps the prior auto-download behavior.
 */
expect fun shouldDeferUpdateInstallToUser(): Boolean
