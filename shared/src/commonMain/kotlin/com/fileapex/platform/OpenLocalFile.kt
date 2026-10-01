package com.fileapex.platform

expect fun openLocalFile(absolutePath: String, displayName: String = "")

/** Shows [absolutePath] selected in the system file manager (Android: the Downloads app). */
expect fun revealInFolder(absolutePath: String)
