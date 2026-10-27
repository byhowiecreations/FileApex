package com.fileapex.platform

expect fun openLocalFile(absolutePath: String, displayName: String = "")

/** Opens the folder that holds [absolutePath] in this app's own file screen. */
fun revealInFolder(absolutePath: String) {
    com.fileapex.presentation.LocalExplorerNavigation.showFile(absolutePath)
}
