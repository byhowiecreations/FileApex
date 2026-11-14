package com.fileapex.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.fileapex.di.FileApexServices
import com.fileapex.domain.clipboard.ClipboardShareCoordinator
import com.fileapex.domain.diagnostics.BatteryDiagnostics
import com.fileapex.domain.widget.CachedBatteryReading
import com.fileapex.domain.widget.WidgetActionTarget
import com.fileapex.domain.widget.WidgetTargetBinding
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.BackupScheduler
import com.fileapex.platform.FileApexAndroidBootstrap
import com.fileapex.platform.PlatformClipboard
import com.fileapex.presentation.devices.PeerBatteryFetch
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Connects [shared] widget events and repositories back to the Android [composeApp] widget providers.
 */
object AndroidWidgetBridge {
    @Volatile
    var onRefreshAllWidgets: (Context) -> Unit = {}

    @Volatile
    var onRefreshBulletinWidgets: (Context) -> Unit = {}

    @Volatile
    var onOpenMainActivityDestination: (Context, WidgetDeepLinks.MainDestination) -> Unit = { _, _ -> }

    fun notifyAllChanged(context: Context) {
        onRefreshAllWidgets(context.applicationContext)
    }

    fun notifyBulletinChanged(context: Context) {
        onRefreshBulletinWidgets(context.applicationContext)
    }
}

interface WidgetActionDeps {
    val configStore: WidgetConfigStore
    fun ensureInitialized(): Boolean
    suspend fun listPairedDevices(): List<Pair<String, String>>
    fun onlinePairedDeviceIds(): List<String>
    suspend fun hasAnyPairedDevice(): Boolean
    suspend fun resolveDeviceName(deviceId: String): String
    fun resolveLatestMedia(target: WidgetActionTarget): LatestMediaResult
    suspend fun sendFileToDevices(absolutePath: String, deviceIds: List<String>): String
    suspend fun sendClipboardToDevice(deviceId: String): String
    suspend fun pullClipboardFromDevice(deviceId: String): String?
    fun setLocalClipboard(text: String)
    suspend fun fetchBattery(deviceId: String): BatteryDiagnostics?
    suspend fun ringDevice(deviceId: String): Boolean
    fun runBackupNow()
    suspend fun toggleQueuePause(): Boolean?
    fun openQuickPostDialog()
    fun openBulletinBoard()
    fun openBrowseDevice(deviceId: String)
    fun openFilePickerForSend(binding: WidgetTargetBinding)
    fun openFilePickerForGuestLink()
    fun openDevicePickerForAction(target: WidgetActionTarget)
    fun openBatteryPopup(preferredDeviceId: String?)
    fun openFriends()
    fun openStoragePermissionPrompt()
    fun notifyWidgetsChanged()
    fun stringRes(key: String, vararg args: Any): String
}

data class BatteryPollEntry(
    val deviceId: String,
    val deviceName: String,
    val diagnostics: BatteryDiagnostics?,
    val reachable: Boolean
)

sealed interface WidgetDispatchResult {
    data class Message(val text: String) : WidgetDispatchResult
    data object LaunchedUi : WidgetDispatchResult
}

/**
 * Executes any widget tap by delegating directly to existing domain coordinators and repositories.
 */
class AndroidWidgetActionDispatcher(
    private val deps: WidgetActionDeps
) {
    suspend fun dispatch(
        target: WidgetActionTarget,
        binding: WidgetTargetBinding
    ): WidgetDispatchResult {
        if (!deps.ensureInitialized()) {
            return WidgetDispatchResult.Message(deps.stringRes("widget_storage_locked"))
        }
        return when (target) {
            WidgetActionTarget.POST_TO_BOARD -> {
                deps.openQuickPostDialog()
                WidgetDispatchResult.LaunchedUi
            }
            WidgetActionTarget.OPEN_BULLETIN -> {
                deps.openBulletinBoard()
                WidgetDispatchResult.LaunchedUi
            }
            WidgetActionTarget.OPEN_FRIENDS -> {
                deps.openFriends()
                WidgetDispatchResult.LaunchedUi
            }
            WidgetActionTarget.BACKUP_NOW -> {
                deps.runBackupNow()
                WidgetDispatchResult.Message(deps.stringRes("widget_backup_started"))
            }
            WidgetActionTarget.TOGGLE_QUEUE_PAUSE -> handleQueueToggle()
            WidgetActionTarget.SHARE_AS_LINK -> {
                deps.openFilePickerForGuestLink()
                WidgetDispatchResult.LaunchedUi
            }
            WidgetActionTarget.SEND_FILE -> {
                deps.openFilePickerForSend(binding)
                WidgetDispatchResult.LaunchedUi
            }
            WidgetActionTarget.SEND_LAST_PHOTO,
            WidgetActionTarget.SEND_LAST_SCREENSHOT,
            WidgetActionTarget.SEND_LAST_VIDEO,
            WidgetActionTarget.SEND_LAST_DOWNLOAD -> handleSendLatestMedia(target, binding)
            WidgetActionTarget.SEND_CLIPBOARD -> handleSendClipboard(binding)
            WidgetActionTarget.PULL_CLIPBOARD -> handleSingleDeviceAction(target, binding) { pullClipboard(it) }
            WidgetActionTarget.BROWSE_DEVICE -> handleSingleDeviceAction(target, binding) { deviceId ->
                deps.openBrowseDevice(deviceId)
                WidgetDispatchResult.LaunchedUi
            }
            WidgetActionTarget.CHECK_BATTERY -> {
                if (!deps.hasAnyPairedDevice()) {
                    WidgetDispatchResult.Message(deps.stringRes("widget_no_paired_devices"))
                } else {
                    val preferred = (binding as? WidgetTargetBinding.SpecificDevice)?.deviceId
                    deps.openBatteryPopup(preferred)
                    WidgetDispatchResult.LaunchedUi
                }
            }
            WidgetActionTarget.RING_PHONE -> handleSingleDeviceAction(target, binding) { ringPhone(it) }
        }
    }

    private suspend fun handleQueueToggle(): WidgetDispatchResult = when (deps.toggleQueuePause()) {
        true -> WidgetDispatchResult.Message(deps.stringRes("widget_queue_paused"))
        false -> WidgetDispatchResult.Message(deps.stringRes("widget_queue_resumed"))
        null -> WidgetDispatchResult.Message(deps.stringRes("widget_queue_empty"))
    }

    private suspend fun handleSendLatestMedia(
        target: WidgetActionTarget,
        binding: WidgetTargetBinding
    ): WidgetDispatchResult {
        val deviceIds = when (binding) {
            is WidgetTargetBinding.SpecificDevice -> listOf(binding.deviceId)
            WidgetTargetBinding.AllOnline -> deps.onlinePairedDeviceIds()
            WidgetTargetBinding.AskEachTime -> {
                if (!deps.hasAnyPairedDevice()) {
                    return WidgetDispatchResult.Message(deps.stringRes("widget_no_paired_devices"))
                }
                deps.openDevicePickerForAction(target)
                return WidgetDispatchResult.LaunchedUi
            }
        }
        if (deviceIds.isEmpty()) {
            return WidgetDispatchResult.Message(deps.stringRes("widget_no_online_devices"))
        }
        return when (val media = deps.resolveLatestMedia(target)) {
            LatestMediaResult.PermissionRequired -> {
                deps.openStoragePermissionPrompt()
                WidgetDispatchResult.Message(deps.stringRes("widget_storage_permission_needed"))
            }
            LatestMediaResult.NotFound -> WidgetDispatchResult.Message(deps.stringRes("widget_no_recent_file"))
            is LatestMediaResult.Found -> cancellableCatching {
                val msg = deps.sendFileToDevices(media.file.absolutePath, deviceIds)
                WidgetDispatchResult.Message(msg.ifBlank { deps.stringRes("widget_sent_file", media.file.name) })
            }.getOrElse { error ->
                WidgetDispatchResult.Message(error.message ?: deps.stringRes("widget_action_failed"))
            }
        }
    }

    private suspend fun handleSendClipboard(binding: WidgetTargetBinding): WidgetDispatchResult {
        val deviceIds = when (binding) {
            is WidgetTargetBinding.SpecificDevice -> listOf(binding.deviceId)
            WidgetTargetBinding.AllOnline -> deps.onlinePairedDeviceIds()
            WidgetTargetBinding.AskEachTime -> {
                if (!deps.hasAnyPairedDevice()) {
                    return WidgetDispatchResult.Message(deps.stringRes("widget_no_paired_devices"))
                }
                deps.openDevicePickerForAction(WidgetActionTarget.SEND_CLIPBOARD)
                return WidgetDispatchResult.LaunchedUi
            }
        }
        if (deviceIds.isEmpty()) {
            return WidgetDispatchResult.Message(deps.stringRes("widget_no_online_devices"))
        }
        var sentCount = 0
        var lastError: String? = null
        for (deviceId in deviceIds) {
            cancellableCatching {
                deps.sendClipboardToDevice(deviceId)
                sentCount++
            }.onFailure { lastError = it.message }
        }
        return if (sentCount > 0) {
            WidgetDispatchResult.Message(deps.stringRes("widget_clipboard_sent_count", sentCount))
        } else {
            WidgetDispatchResult.Message(lastError ?: deps.stringRes("widget_action_failed"))
        }
    }

    private suspend fun handleSingleDeviceAction(
        target: WidgetActionTarget,
        binding: WidgetTargetBinding,
        runForDevice: suspend (String) -> WidgetDispatchResult
    ): WidgetDispatchResult = when (binding) {
        is WidgetTargetBinding.SpecificDevice -> runForDevice(binding.deviceId)
        WidgetTargetBinding.AllOnline,
        WidgetTargetBinding.AskEachTime -> {
            if (!deps.hasAnyPairedDevice()) {
                WidgetDispatchResult.Message(deps.stringRes("widget_no_paired_devices"))
            } else {
                deps.openDevicePickerForAction(target)
                WidgetDispatchResult.LaunchedUi
            }
        }
    }

    private suspend fun pullClipboard(deviceId: String): WidgetDispatchResult = cancellableCatching {
        val text = deps.pullClipboardFromDevice(deviceId)
        if (text.isNullOrEmpty()) {
            WidgetDispatchResult.Message(deps.stringRes("widget_clipboard_empty"))
        } else {
            deps.setLocalClipboard(text)
            val name = deps.resolveDeviceName(deviceId)
            WidgetDispatchResult.Message(deps.stringRes("widget_clipboard_pulled", name))
        }
    }.getOrElse { error ->
        WidgetDispatchResult.Message(error.message ?: deps.stringRes("widget_action_failed"))
    }

    suspend fun pollAllPairedDevicesBattery(): List<BatteryPollEntry> {
        if (!deps.ensureInitialized()) return emptyList()
        val paired = deps.listPairedDevices()
        if (paired.isEmpty()) return emptyList()
        val results = coroutineScope {
            paired.map { (deviceId, deviceName) ->
                async {
                    val battery = cancellableCatching { deps.fetchBattery(deviceId) }.getOrNull()
                    val pct = battery?.levelPercent
                    val valid = pct != null && pct in 0..100
                    if (valid) {
                        deps.configStore.saveBatteryReading(
                            deviceId = deviceId,
                            reading = CachedBatteryReading(
                                levelPercent = pct!!,
                                recordedAtEpochMs = TimeUtils.now(),
                                chargingState = battery?.chargingState.orEmpty()
                            )
                        )
                    }
                    BatteryPollEntry(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        diagnostics = battery.takeIf { valid },
                        reachable = valid
                    )
                }
            }.awaitAll()
        }
        if (results.any { it.reachable }) {
            deps.notifyWidgetsChanged()
        }
        return results
    }

    private suspend fun ringPhone(deviceId: String): WidgetDispatchResult = cancellableCatching {
        val ok = deps.ringDevice(deviceId)
        val name = deps.resolveDeviceName(deviceId)
        if (ok) {
            WidgetDispatchResult.Message(deps.stringRes("widget_ring_sent", name))
        } else {
            WidgetDispatchResult.Message(deps.stringRes("widget_device_unreachable", name))
        }
    }.getOrElse { error ->
        WidgetDispatchResult.Message(error.message ?: deps.stringRes("widget_action_failed"))
    }
}

class LiveWidgetActionDeps(
    private val context: Context
) : WidgetActionDeps {
    private val appContext = context.applicationContext
    override val configStore: WidgetConfigStore by lazy { WidgetConfigStore(appContext) }
    private val mediaResolver: LatestMediaResolver by lazy { LatestMediaResolver(appContext) }

    override fun ensureInitialized(): Boolean = FileApexAndroidBootstrap.ensureInitialized(appContext)

    override fun onlinePairedDeviceIds(): List<String> =
        FileApexServices.presenceMonitor.onlineDeviceIds.value.toList()

    override suspend fun listPairedDevices(): List<Pair<String, String>> =
        FileApexServices.deviceRepository.listDevices()
            .map { it.deviceId to it.deviceName.ifBlank { it.deviceId } }

    override suspend fun hasAnyPairedDevice(): Boolean =
        FileApexServices.deviceRepository.listDevices().isNotEmpty()

    override suspend fun resolveDeviceName(deviceId: String): String =
        FileApexServices.deviceRepository.getDevice(deviceId)?.deviceName
            ?.takeIf { it.isNotBlank() } ?: deviceId

    override fun resolveLatestMedia(target: WidgetActionTarget): LatestMediaResult =
        mediaResolver.resolve(target)

    override suspend fun sendFileToDevices(absolutePath: String, deviceIds: List<String>): String =
        FileApexServices.transferQueue
            .sendLocalPathsOrQueue(listOf(absolutePath), deviceIds)
            .message

    override suspend fun sendClipboardToDevice(deviceId: String): String =
        ClipboardShareCoordinator.sendToDevice(deviceId).recipientDeviceName

    override suspend fun pullClipboardFromDevice(deviceId: String): String? {
        val device = FileApexServices.deviceRepository.getDevice(deviceId) ?: return null
        val endpoint = FileApexServices.presenceMonitor.probeNow(device) ?: return null
        return FileApexServices.client.clipboard.pullRemoteClipboard(endpoint.host, endpoint.port)
    }

    override fun setLocalClipboard(text: String) {
        PlatformClipboard.setSystemClipboardText(text)
    }

    override suspend fun fetchBattery(deviceId: String): BatteryDiagnostics? =
        PeerBatteryFetch.fetch(deviceId)

    override suspend fun ringDevice(deviceId: String): Boolean {
        val device = FileApexServices.deviceRepository.getDevice(deviceId) ?: return false
        val endpoint = FileApexServices.presenceMonitor.probeNow(device) ?: return false
        return FileApexServices.client.alerts.triggerDeviceBeepStatus(
            host = endpoint.host,
            port = endpoint.port,
            continuous = false
        ) in 200..299
    }

    override fun runBackupNow() {
        BackupScheduler.runNow(FileApexServices.settings.backupConfig.value)
    }

    override suspend fun toggleQueuePause(): Boolean? =
        FileApexServices.transferQueue.togglePauseAll()

    override fun openQuickPostDialog() {
        appContext.startActivity(WidgetDeepLinks.quickPostIntent(appContext))
    }

    override fun openBulletinBoard() {
        AndroidWidgetBridge.onOpenMainActivityDestination(appContext, WidgetDeepLinks.MainDestination.BulletinBoard)
    }

    override fun openBrowseDevice(deviceId: String) {
        AndroidWidgetBridge.onOpenMainActivityDestination(
            appContext,
            WidgetDeepLinks.MainDestination.BrowseDevice(deviceId)
        )
    }

    override fun openFilePickerForSend(binding: WidgetTargetBinding) {
        appContext.startActivity(WidgetDeepLinks.pickFileForSendIntent(appContext, binding))
    }

    override fun openFilePickerForGuestLink() {
        appContext.startActivity(WidgetDeepLinks.pickFileForGuestLinkIntent(appContext))
    }

    override fun openDevicePickerForAction(target: WidgetActionTarget) {
        appContext.startActivity(WidgetDeepLinks.pickDeviceForActionIntent(appContext, target))
    }

    override fun openBatteryPopup(preferredDeviceId: String?) {
        appContext.startActivity(WidgetDeepLinks.batteryPopupIntent(appContext, preferredDeviceId))
    }

    override fun openFriends() {
        AndroidWidgetBridge.onOpenMainActivityDestination(appContext, WidgetDeepLinks.MainDestination.Friends)
    }

    override fun openStoragePermissionPrompt() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${appContext.packageName}")
            )
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${appContext.packageName}")
            )
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }.onFailure {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    appContext.startActivity(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    override fun notifyWidgetsChanged() {
        AndroidWidgetBridge.notifyAllChanged(appContext)
    }

    override fun stringRes(key: String, vararg args: Any): String = AppI18n.t(key, *args)
}
