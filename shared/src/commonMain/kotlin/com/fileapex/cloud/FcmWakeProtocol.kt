package com.fileapex.cloud

/** Silent FCM data payload keys — no notification channel; high-priority data-only wake. */
object FcmWakeProtocol {
    const val TYPE_PRESENCE_WAKE = "presence_wake"
    const val KEY_TYPE = "type"
    const val KEY_SOURCE_DEVICE_ID = "sourceDeviceId"
    const val KEY_EPOCH_MS = "epochMs"
}
