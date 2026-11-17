package com.fileapex.data.settings

import com.fileapex.domain.clipboard.ClipboardShareMode
import com.fileapex.domain.clipboard.ClipboardSharePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Clipboard sharing choices, acknowledgements and the devices it is sent to. The clipboard key pair is not here:
 * it belongs to the identity key stores. Platform side effects of a change stay with the caller.
 */
class ClipboardSettingsStore(private val store: SettingsKvStore) {
    private val sharing = BooleanSetting(store, BaseAppSettings.KEY_CLIPBOARD_SHARING)
    private val disclosure = BooleanSetting(store, BaseAppSettings.KEY_CLIPBOARD_DISCLOSURE_ACK)
    private val accessibilityDisclosure = BooleanSetting(store, BaseAppSettings.KEY_ACCESSIBILITY_DISCLOSURE_ACK)
    private val installPackagesDisclosure = BooleanSetting(store, BaseAppSettings.KEY_INSTALL_PACKAGES_DISCLOSURE_ACK)
    private val multiCopyIntro = BooleanSetting(store, BaseAppSettings.KEY_MULTI_COPY_INTRO)
    private val targetConfiguredFlag = BooleanSetting(store, BaseAppSettings.KEY_CLIPBOARD_TARGET_CONFIGURED)
    private val optInPromptShownFlag = BooleanSetting(store, BaseAppSettings.KEY_CLIPBOARD_OPT_IN_PROMPT_SHOWN)
    private val viaCellular = BooleanSetting(store, BaseAppSettings.KEY_CLIPBOARD_VIA_CELLULAR)
    private val accessibility = BooleanSetting(store, BaseAppSettings.KEY_CLIPBOARD_ACCESSIBILITY)
    private val sendNotification = BooleanSetting(store, BaseAppSettings.KEY_CLIPBOARD_SEND_NOTIFICATION)
    private val shizuku = BooleanSetting(store, BaseAppSettings.KEY_CLIPBOARD_SHIZUKU)
    private val autoSend = BooleanSetting(store, BaseAppSettings.KEY_CLIPBOARD_AUTO_SEND)
    private val images = BooleanSetting(store, BaseAppSettings.KEY_CLIPBOARD_IMAGES, default = true)
    private val shareModeFlow = MutableStateFlow(ClipboardShareMode.fromStorage(store.getString(BaseAppSettings.KEY_CLIPBOARD_SHARE_MODE, "")))
    private val targetDevicesFlow = MutableStateFlow(ClipboardSharePolicy.parseDeviceIdSet(store.getString(BaseAppSettings.KEY_CLIPBOARD_TARGET_DEVICES, "")))

    val sharingEnabled: StateFlow<Boolean> = sharing.flow
    val disclosureAcknowledged: StateFlow<Boolean> = disclosure.flow
    val accessibilityDisclosureAcknowledged: StateFlow<Boolean> = accessibilityDisclosure.flow
    val installPackagesDisclosureAcknowledged: StateFlow<Boolean> = installPackagesDisclosure.flow
    val multiCopyIntroAcknowledged: StateFlow<Boolean> = multiCopyIntro.flow
    val targetConfigured: StateFlow<Boolean> = targetConfiguredFlag.flow
    val optInPromptShown: StateFlow<Boolean> = optInPromptShownFlag.flow
    val viaCellularEnabled: StateFlow<Boolean> = viaCellular.flow
    val accessibilityEnabled: StateFlow<Boolean> = accessibility.flow
    val sendNotificationEnabled: StateFlow<Boolean> = sendNotification.flow
    val shizukuEnabled: StateFlow<Boolean> = shizuku.flow
    val autoSendEnabled: StateFlow<Boolean> = autoSend.flow
    val imagesEnabled: StateFlow<Boolean> = images.flow
    val shareMode: StateFlow<ClipboardShareMode> = shareModeFlow.asStateFlow()
    val targetDeviceIds: StateFlow<Set<String>> = targetDevicesFlow.asStateFlow()

    fun setSharingEnabled(value: Boolean) = sharing.set(value)
    fun setDisclosureAcknowledged(value: Boolean) = disclosure.set(value)
    fun setAccessibilityDisclosureAcknowledged(value: Boolean) = accessibilityDisclosure.set(value)
    fun setInstallPackagesDisclosureAcknowledged(value: Boolean) = installPackagesDisclosure.set(value)
    fun setMultiCopyIntroAcknowledged(value: Boolean) = multiCopyIntro.set(value)
    fun setTargetConfigured(value: Boolean) = targetConfiguredFlag.set(value)
    fun setOptInPromptShown(value: Boolean) = optInPromptShownFlag.set(value)
    fun setViaCellularEnabled(value: Boolean) = viaCellular.set(value)
    fun setAccessibilityEnabled(value: Boolean) = accessibility.set(value)
    fun setSendNotificationEnabled(value: Boolean) = sendNotification.set(value)
    fun setShizukuEnabled(value: Boolean) = shizuku.set(value)
    fun setAutoSendEnabled(value: Boolean) = autoSend.set(value)
    fun setImagesEnabled(value: Boolean) = images.set(value)

    fun setShareMode(mode: ClipboardShareMode) {
        store.putString(BaseAppSettings.KEY_CLIPBOARD_SHARE_MODE, mode.name)
        shareModeFlow.value = mode
    }

    fun setTargetDeviceIds(deviceIds: Set<String>) {
        val encoded = ClipboardSharePolicy.encodeDeviceIdSet(deviceIds)
        store.putString(BaseAppSettings.KEY_CLIPBOARD_TARGET_DEVICES, encoded)
        targetDevicesFlow.value = ClipboardSharePolicy.parseDeviceIdSet(encoded)
    }

    fun setTargetDevice(deviceId: String, selected: Boolean) {
        val trimmed = deviceId.trim()
        if (trimmed.isEmpty()) return
        setTargetDeviceIds(if (selected) targetDevicesFlow.value + trimmed else targetDevicesFlow.value - trimmed)
    }

    /** The settings store has been cleared; bring the in-memory state back to the defaults. */
    fun forgetInMemory() {
        listOf(
            sharing, disclosure, accessibilityDisclosure, installPackagesDisclosure, multiCopyIntro, targetConfiguredFlag,
            optInPromptShownFlag, viaCellular, accessibility, sendNotification, shizuku, autoSend, images
        ).forEach { it.forget() }
        shareModeFlow.value = ClipboardShareMode.UNSET
        targetDevicesFlow.value = emptySet()
    }
}
