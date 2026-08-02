package com.fileapex.platform

actual fun notifyFilesReceived(fileNames: List<String>) {
    // Desktop has no receive notification UI yet; phones are the primary receive surface.
}
