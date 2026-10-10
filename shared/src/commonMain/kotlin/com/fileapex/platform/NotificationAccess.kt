package com.fileapex.platform

import com.fileapex.domain.notifications.NotificationAppInfo
import com.fileapex.domain.notifications.NotificationPayload

const val EXTRA_OPEN_SETTINGS_PAGE = "com.fileapex.extra.OPEN_SETTINGS_PAGE"

/** True when this device lets FileApex read other apps' notifications. Always false on desktop. */
expect fun isNotificationAccessGranted(): Boolean

expect fun openNotificationAccessSettings()

/**
 * Posts a notification on this device that opens [page] when tapped; the only way to bring a
 * settings screen up from the background. [requesterName] is the paired device that asked.
 */
expect fun showPhoneSettingsPrompt(page: String, requesterName: String)

expect fun listNotificationApps(): List<NotificationAppInfo>

/** Dismisses these notifications on this device. Only apps in [allowedPackages] are touched. */
expect fun dismissPhoneNotifications(keys: List<String>, allowedPackages: Set<String>)

/** Notifications this device is showing right now from [allowedPackages]; empty when access is off or on desktop. */
expect fun snapshotPhoneNotifications(allowedPackages: Set<String>): List<NotificationPayload>

/** Fills in the reply box of one active notification. False when it is gone or has no reply box. */
expect fun replyToPhoneNotification(key: String, text: String, allowedPackages: Set<String>): Boolean

/** True where this OS can pop up a notification received from the paired phone. */
expect fun supportsDevicePopups(): Boolean

expect fun showDevicePopup(title: String, text: String)
