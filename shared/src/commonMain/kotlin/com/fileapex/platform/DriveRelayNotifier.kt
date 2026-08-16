package com.fileapex.platform

/**
 * Google Drive Relay notifications — channel is created and the Settings toggle is
 * switched on when Drive is enabled and the grant has been verified.
 */
expect object DriveRelayNotifier {
    fun onDriveEnabledAndGranted()
    fun notifyPosted(fileNames: List<String>, targetNames: List<String>)
    fun notifyFailed(fileName: String, queued: Boolean)
    fun notifyRetrieved(fileNames: List<String>)
    fun retractRetrieved(fileName: String)
}
