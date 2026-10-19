package com.fileapex.platform

/** User-facing short label for where received files land on this platform. */
expect fun downloadsFolderDisplayLabel(): String

/** Public Downloads/FileApex directory for saving remote files onto this device. */
expect fun defaultDownloadsDir(): String

/**
 * Inbound file landing folders.
 *
 * Android public folder: `Download/FileApex`
 * macOS/desktop: `~/Downloads/FileApex`
 * Senders do not compute this path for another device: they send `@inbox/<name>` (see InboxTarget) and the
 * receiver expands it to its own folder.
 */
object DownloadsPaths {
    const val FOLDER_NAME = "FileApex"

    fun displayLabel(): String = downloadsFolderDisplayLabel()
}
