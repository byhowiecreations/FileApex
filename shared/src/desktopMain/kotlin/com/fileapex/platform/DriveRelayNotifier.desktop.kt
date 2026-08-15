package com.fileapex.platform

import com.fileapex.di.FileApexServices

actual object DriveRelayNotifier {
    actual fun onDriveEnabledAndGranted() {
        FileApexServices.settings.setDriveRelayNotificationsEnabled(true)
    }

    actual fun notifyPosted(fileNames: List<String>, targetNames: List<String>) {
        if (!FileApexServices.settings.driveRelayNotificationsEnabled.value) return
        if (fileNames.isEmpty()) return
        println("DriveRelayNotifier: posted ${fileNames.joinToString()} → ${targetNames.joinToString()}")
    }

    actual fun notifyFailed(fileName: String, queued: Boolean) {
        if (!FileApexServices.settings.driveRelayNotificationsEnabled.value) return
        println("DriveRelayNotifier: failed $fileName queued=$queued")
    }

    actual fun notifyRetrieved(fileNames: List<String>) {
        if (!FileApexServices.settings.driveRelayNotificationsEnabled.value) return
        if (fileNames.isEmpty()) return
        println("DriveRelayNotifier: retrieved ${fileNames.joinToString()}")
    }
}
