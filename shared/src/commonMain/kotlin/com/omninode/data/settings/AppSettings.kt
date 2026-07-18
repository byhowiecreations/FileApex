package com.omninode.data.settings

import kotlinx.coroutines.flow.StateFlow

interface AppSettings {
    val googleAccountLinkEnabled: StateFlow<Boolean>
    val multiCopyIntroAcknowledged: StateFlow<Boolean>
    /** When true, this device shows a notification after successfully receiving files. Default off. */
    val fileTransferNotificationsEnabled: StateFlow<Boolean>
    /** When true, scanners must supply this device's PIN to pair. Default off. */
    val pinRequiredEnabled: StateFlow<Boolean>
    /** Local PIN others must enter when [pinRequiredEnabled] is on. */
    val devicePin: StateFlow<String>
    /** When true, check GitHub Releases for updates on launch. Default off. */
    val autoUpdateEnabled: StateFlow<Boolean>

    fun setGoogleAccountLinkEnabled(enabled: Boolean)
    fun setMultiCopyIntroAcknowledged(acknowledged: Boolean)
    fun setFileTransferNotificationsEnabled(enabled: Boolean)
    fun setPinRequiredEnabled(enabled: Boolean)
    fun setDevicePin(pinValue: String)
    fun setAutoUpdateEnabled(enabled: Boolean)
}

expect fun createAppSettings(): AppSettings
