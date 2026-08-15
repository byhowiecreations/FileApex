package com.fileapex.cloud.drive

/** No-op on Android. Desktop starts the OAuth loopback listener. */
expect fun installDriveGrantRuntime()

expect fun startDriveGrantIfNeeded(): Boolean
