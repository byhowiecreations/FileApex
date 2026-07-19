package com.omninode.data.settings

import kotlinx.coroutines.flow.StateFlow

interface AppSettings {
    val googleAccountLinkEnabled: StateFlow<Boolean>
    /** Email of the linked Google account when [googleAccountLinkEnabled] is on; empty otherwise. */
    val googleAccountEmail: StateFlow<String>
    /** Firebase Auth UID for Firestore path users/{uid}/devices. Empty when unlinked. */
    val googleAccountUid: StateFlow<String>
    val multiCopyIntroAcknowledged: StateFlow<Boolean>
    /** When true, this device shows a notification after successfully receiving files. Default off. */
    val fileTransferNotificationsEnabled: StateFlow<Boolean>
    /** When true, scanners must supply this device's PIN to pair. Default off. */
    val pinRequiredEnabled: StateFlow<Boolean>
    /** Local PIN others must enter when [pinRequiredEnabled] is on. */
    val devicePin: StateFlow<String>
    /** Browse unlock idle window for peers (this device as the browser). Default [PinIdleTimeout.FiveMinutes]. */
    val pinIdleTimeout: StateFlow<PinIdleTimeout>
    /** When true, check GitHub Releases for updates on the configured schedule. Default off. */
    val autoUpdateEnabled: StateFlow<Boolean>
    /** Unit for [autoUpdateIntervalAmount]. Default [UpdateCheckUnit.Days]. */
    val autoUpdateIntervalUnit: StateFlow<UpdateCheckUnit>
    /** Amount paired with [autoUpdateIntervalUnit]. Default 1. */
    val autoUpdateIntervalAmount: StateFlow<Int>
    /** Epoch millis of the last completed update check (0 = never). */
    val lastUpdateCheckEpochMs: StateFlow<Long>

    fun setGoogleAccountLinkEnabled(enabled: Boolean)
    fun setGoogleAccountEmail(email: String)
    fun setGoogleAccountUid(uid: String)
    fun setMultiCopyIntroAcknowledged(acknowledged: Boolean)
    fun setFileTransferNotificationsEnabled(enabled: Boolean)
    fun setPinRequiredEnabled(enabled: Boolean)
    fun setDevicePin(pinValue: String)
    fun setPinIdleTimeout(timeout: PinIdleTimeout)
    fun setAutoUpdateEnabled(enabled: Boolean)
    fun setAutoUpdateInterval(unit: UpdateCheckUnit, amount: Int)
    fun setLastUpdateCheckEpochMs(epochMs: Long)

    fun autoUpdateIntervalMillis(): Long {
        return UpdateCheckFrequency.toMillis(
            autoUpdateIntervalUnit.value,
            autoUpdateIntervalAmount.value
        )
    }
}

expect fun createAppSettings(): AppSettings
