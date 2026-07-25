package com.fileapex.platform

/** Platform storage root used as the explorer start path. */
expect fun defaultStorageRoot(): String

/** Human-readable device name for pairing. */
expect fun platformDeviceName(): String
