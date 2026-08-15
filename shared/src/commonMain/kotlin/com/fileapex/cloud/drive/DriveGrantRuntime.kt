package com.fileapex.cloud.drive

/** Desktop Drive OAuth listener + browser start. No-op on Android. */
expect fun installDriveGrantRuntime()

/** Opens the Drive consent page when this device still needs a grant. */
expect fun startDriveGrantIfNeeded(): Boolean
