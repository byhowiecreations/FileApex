package com.fileapex.platform

/** True where the OS gates protected folders behind a Full Disk Access switch (macOS). */
expect fun fullDiskAccessSupported(): Boolean

/** True when that switch is on for this app. Reads the disk, so call it off the UI thread. */
expect fun isFullDiskAccessGranted(): Boolean

expect fun openFullDiskAccessSettings()
