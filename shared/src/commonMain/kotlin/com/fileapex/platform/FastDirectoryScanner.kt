package com.fileapex.platform

import com.fileapex.domain.model.RemoteFileItem

expect fun fastScanDirectory(absolutePath: String): Pair<List<RemoteFileItem>, List<RemoteFileItem>>

/**
 * First touch of a protected folder (Desktop, Documents, Downloads).
 * On macOS this must run on the UI thread or the Allow dialog waits several seconds.
 */
expect suspend fun prepareLocalDirectoryAccess(absolutePath: String)
