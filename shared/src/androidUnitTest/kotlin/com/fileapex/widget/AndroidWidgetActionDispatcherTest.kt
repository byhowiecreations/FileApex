package com.fileapex.widget

import android.content.Context
import com.fileapex.domain.diagnostics.BatteryDiagnostics
import com.fileapex.domain.widget.WidgetActionTarget
import com.fileapex.domain.widget.WidgetTargetBinding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidWidgetActionDispatcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: WidgetConfigStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("fileapex_widgets_dispatch_test", Context.MODE_PRIVATE).edit().clear().commit()
        store = WidgetConfigStore(context, prefsName = "fileapex_widgets_dispatch_test")
    }

    @Test
    fun refusesExecutionWhenStorageIsLocked() = runBlocking {
        var openedBulletin = false
        val dispatcher = AndroidWidgetActionDispatcher(
            deps = FakeDeps(storageReady = false, onOpenBulletin = { openedBulletin = true })
        )
        val result = dispatcher.dispatch(WidgetActionTarget.OPEN_BULLETIN, WidgetTargetBinding.AskEachTime)
        assertTrue(result is WidgetDispatchResult.Message)
        assertEquals(false, openedBulletin)
    }

    @Test
    fun checkBatteryOpensOneTapBatteryPopupAndPollsAllPairedDevicesConcurrently() = runBlocking {
        var popupOpenedPreferred: String? = "unset"
        var refreshCount = 0
        val dispatcher = AndroidWidgetActionDispatcher(
            deps = FakeDeps(
                pairedDevices = listOf("peer-1" to "Pixel 10", "peer-2" to "Mac Studio"),
                batteryByDevice = mapOf(
                    "peer-1" to BatteryDiagnostics(levelPercent = 74, chargingState = "Discharging"),
                    "peer-2" to BatteryDiagnostics(levelPercent = 91, chargingState = "Charging")
                ),
                onOpenBatteryPopup = { preferred -> popupOpenedPreferred = preferred },
                onRefreshWidgets = { refreshCount++ }
            )
        )
        val result = dispatcher.dispatch(
            target = WidgetActionTarget.CHECK_BATTERY,
            binding = WidgetTargetBinding.AskEachTime
        )
        assertTrue(result is WidgetDispatchResult.LaunchedUi)
        assertEquals(null, popupOpenedPreferred)

        val entries = dispatcher.pollAllPairedDevicesBattery()
        assertEquals(2, entries.size)
        assertEquals(74, store.loadBatteryReading("peer-1")?.levelPercent)
        assertEquals(91, store.loadBatteryReading("peer-2")?.levelPercent)
        assertEquals(1, refreshCount)
    }

    @Test
    fun ringPhoneUsesNonContinuousBeepAndClipboardPullCopiesRemoteText() = runBlocking {
        val ringedDevices = mutableListOf<String>()
        var localClipboard = ""
        val dispatcher = AndroidWidgetActionDispatcher(
            deps = FakeDeps(
                remoteClipboardByDevice = mapOf("peer-2" to "hello from peer"),
                onRingDevice = { id -> ringedDevices += id; true },
                onSetLocalClipboard = { text -> localClipboard = text }
            )
        )
        dispatcher.dispatch(WidgetActionTarget.RING_PHONE, WidgetTargetBinding.SpecificDevice("peer-2"))
        assertEquals(listOf("peer-2"), ringedDevices)

        dispatcher.dispatch(WidgetActionTarget.PULL_CLIPBOARD, WidgetTargetBinding.SpecificDevice("peer-2"))
        assertEquals("hello from peer", localClipboard)
    }

    @Test
    fun sendLastPhotoQueuesFileToAllOnlineDevicesAndHandlesMissingPermission() = runBlocking {
        val photo = File(tempFolder.root, "latest.jpg").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val sentCalls = mutableListOf<Pair<String, List<String>>>()

        val lockedDispatcher = AndroidWidgetActionDispatcher(
            deps = FakeDeps(latestMedia = LatestMediaResult.PermissionRequired)
        )
        var storagePromptOpened = false
        val lockedDeps = FakeDeps(
            onlineDeviceIds = listOf("peer-a"),
            latestMedia = LatestMediaResult.PermissionRequired,
            onOpenStorageAccess = { storagePromptOpened = true }
        )
        AndroidWidgetActionDispatcher(lockedDeps).dispatch(
            WidgetActionTarget.SEND_LAST_PHOTO,
            WidgetTargetBinding.AllOnline
        )
        assertTrue(storagePromptOpened)

        val okDispatcher = AndroidWidgetActionDispatcher(
            deps = FakeDeps(
                latestMedia = LatestMediaResult.Found(photo),
                onlineDeviceIds = listOf("peer-a", "peer-b"),
                onSendFiles = { path, ids -> sentCalls += path to ids; "Queued" }
            )
        )
        okDispatcher.dispatch(WidgetActionTarget.SEND_LAST_PHOTO, WidgetTargetBinding.AllOnline)
        assertEquals(listOf(photo.absolutePath to listOf("peer-a", "peer-b")), sentCalls)
    }

    @Test
    fun backupNowAndQueueToggleDelegateDirectlyToDomain() = runBlocking {
        var backupRuns = 0
        var toggled = 0
        val dispatcher = AndroidWidgetActionDispatcher(
            deps = FakeDeps(
                onRunBackupNow = { backupRuns++ },
                onToggleQueuePause = { toggled++; true }
            )
        )
        dispatcher.dispatch(WidgetActionTarget.BACKUP_NOW, WidgetTargetBinding.AskEachTime)
        dispatcher.dispatch(WidgetActionTarget.TOGGLE_QUEUE_PAUSE, WidgetTargetBinding.AskEachTime)
        assertEquals(1, backupRuns)
        assertEquals(1, toggled)
    }

    private inner class FakeDeps(
        private val storageReady: Boolean = true,
        private val pairedDevices: List<Pair<String, String>> = listOf("peer-1" to "peer-1"),
        private val onlineDeviceIds: List<String> = emptyList(),
        private val batteryByDevice: Map<String, BatteryDiagnostics> = emptyMap(),
        private val remoteClipboardByDevice: Map<String, String?> = emptyMap(),
        private val latestMedia: LatestMediaResult = LatestMediaResult.NotFound,
        private val onOpenBulletin: () -> Unit = {},
        private val onOpenBatteryPopup: (String?) -> Unit = {},
        private val onRingDevice: suspend (String) -> Boolean = { true },
        private val onSetLocalClipboard: (String) -> Unit = {},
        private val onOpenStorageAccess: () -> Unit = {},
        private val onSendFiles: suspend (String, List<String>) -> String = { _, _ -> "ok" },
        private val onRunBackupNow: () -> Unit = {},
        private val onToggleQueuePause: suspend () -> Boolean? = { null },
        private val onRefreshWidgets: () -> Unit = {}
    ) : WidgetActionDeps {
        override val configStore: WidgetConfigStore get() = store
        override fun ensureInitialized(): Boolean = storageReady
        override suspend fun listPairedDevices(): List<Pair<String, String>> = pairedDevices
        override fun onlinePairedDeviceIds(): List<String> = onlineDeviceIds
        override suspend fun hasAnyPairedDevice(): Boolean = pairedDevices.isNotEmpty()
        override suspend fun resolveDeviceName(deviceId: String): String =
            pairedDevices.firstOrNull { it.first == deviceId }?.second ?: deviceId
        override fun resolveLatestMedia(target: WidgetActionTarget): LatestMediaResult = latestMedia
        override suspend fun sendFileToDevices(absolutePath: String, deviceIds: List<String>): String =
            onSendFiles(absolutePath, deviceIds)
        override suspend fun sendClipboardToDevice(deviceId: String): String = "sent"
        override suspend fun pullClipboardFromDevice(deviceId: String): String? = remoteClipboardByDevice[deviceId]
        override fun setLocalClipboard(text: String) = onSetLocalClipboard(text)
        override suspend fun fetchBattery(deviceId: String): BatteryDiagnostics? = batteryByDevice[deviceId]
        override suspend fun ringDevice(deviceId: String): Boolean = onRingDevice(deviceId)
        override fun runBackupNow() = onRunBackupNow()
        override suspend fun toggleQueuePause(): Boolean? = onToggleQueuePause()
        override fun openQuickPostDialog() {}
        override fun openBulletinBoard() = onOpenBulletin()
        override fun openBrowseDevice(deviceId: String) {}
        override fun openFilePickerForSend(binding: WidgetTargetBinding) {}
        override fun openFilePickerForGuestLink() {}
        override fun openDevicePickerForAction(target: WidgetActionTarget) {}
        override fun openBatteryPopup(preferredDeviceId: String?) = onOpenBatteryPopup(preferredDeviceId)
        override fun openFriends() {}
        override fun openStoragePermissionPrompt() = onOpenStorageAccess()
        override fun notifyWidgetsChanged() = onRefreshWidgets()
        override fun stringRes(key: String, vararg args: Any): String = key
    }
}
