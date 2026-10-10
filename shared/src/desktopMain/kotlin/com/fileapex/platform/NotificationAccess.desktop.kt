package com.fileapex.platform

import com.fileapex.domain.notifications.NotificationAppInfo
import com.fileapex.domain.notifications.NotificationPayload

actual fun isNotificationAccessGranted(): Boolean = false

actual fun openNotificationAccessSettings() = Unit

actual fun showPhoneSettingsPrompt(page: String, requesterName: String) = Unit

actual fun listNotificationApps(): List<NotificationAppInfo> = emptyList()

actual fun dismissPhoneNotifications(keys: List<String>, allowedPackages: Set<String>) = Unit

actual fun snapshotPhoneNotifications(allowedPackages: Set<String>): List<NotificationPayload> = emptyList()

actual fun replyToPhoneNotification(key: String, text: String, allowedPackages: Set<String>): Boolean = false

actual fun supportsDevicePopups(): Boolean = DesktopPlatformPaths.isWindows()

actual fun showDevicePopup(title: String, text: String) {
    DesktopAwtTrayCoordinator.showBalloon(listOf(title, text).filter { it.isNotBlank() }.joinToString(": "))
}
