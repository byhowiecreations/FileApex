package com.fileapex.platform

/** Opens a local file with the platform default viewer. */
expect fun openLocalFile(absolutePath: String, displayName: String = "")
