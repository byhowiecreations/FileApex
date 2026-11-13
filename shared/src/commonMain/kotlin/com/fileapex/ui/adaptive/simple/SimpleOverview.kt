package com.fileapex.ui.adaptive.simple

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.di.FileApexServices
import com.fileapex.domain.demo.DemoModeState
import com.fileapex.domain.diagnostics.DeviceDiagnosticsFormatter
import com.fileapex.domain.notifications.NotificationBroadcaster
import com.fileapex.domain.notifications.PhoneSettingsPage
import com.fileapex.drop.ui.GuestDropDialog
import com.fileapex.friends.ui.rememberFriendFilePicker
import com.fileapex.i18n.stringRes
import com.fileapex.iroh.IrohRuntime
import com.fileapex.platform.OnboardingPermissionStep
import com.fileapex.presentation.DeviceListRow
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.ui.DemoModeBanner
import com.fileapex.ui.devices.ReachedByField
import com.fileapex.ui.dnd.deviceFileDropTarget
import com.fileapex.util.cancellableCatching
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun SimpleOverview(
    devicesViewModel: DevicesViewModel,
    rows: List<DeviceListRow>,
    active: DeviceListRow?,
    onboardingSteps: List<OnboardingPermissionStep>,
    onAddDevice: () -> Unit,
    onJoinDevice: () -> Unit,
    onBrowseDevice: () -> Unit,
    onOpenSplitView: () -> Unit,
    onOpenBulletinBoard: () -> Unit,
    onOpenDeviceDetails: () -> Unit,
    onLocateDevice: () -> Unit,
    onOpenBackgroundSetup: () -> Unit,
    onFilesDropped: (List<String>) -> Unit,
    showDevicesCard: Boolean = false,
    onDevicesAnchor: (Rect) -> Unit = {},
    onOpenDevices: () -> Unit = {}
) {
    val state by devicesViewModel.uiState.collectAsState()
    val isInitialLoadComplete by devicesViewModel.isInitialLoadComplete.collectAsState()
    val reachedViaMap by FileApexServices.presenceMonitor.reachedVia.collectAsState()
    val isDemoActive by DemoModeState.isDemoModeActive.collectAsState()
    var dropHover by remember { mutableStateOf(false) }
    var shareLinkFile by remember { mutableStateOf<File?>(null) }
    var postPairBannerDismissed by remember { mutableStateOf(false) }
    val linkFilePicker = rememberFriendFilePicker(
        titleKey = "share_link_title",
        viewModelKey = "simple-link-file-pick"
    ) { picked ->
        picked.firstOrNull()?.let { shareLinkFile = it }
    }
    val sendFilePicker = rememberFriendFilePicker(
        titleKey = "choose_files",
        viewModelKey = "simple-send-file-pick"
    ) { picked ->
        val targetDevice = active?.takeIf { it.online } ?: active ?: rows.firstOrNull()
        if (picked.isNotEmpty() && targetDevice != null) {
            onFilesDropped(picked.map { it.absolutePath })
        }
    }
    shareLinkFile?.let { file ->
        GuestDropDialog(file = file, onDismiss = { shareLinkFile = null })
    }

    val connected = active?.takeIf { it.online } ?: active ?: rows.firstOrNull()
    val details = state.deviceDetails?.takeIf { it.deviceId == connected?.deviceId }
    LaunchedEffect(connected?.deviceId, connected?.online) {
        if (
            SimpleOverviewAutoRefreshPolicy.shouldAutoFetchDetails(
                deviceId = connected?.deviceId,
                online = connected?.online == true,
                cachedDetailsDeviceId = state.deviceDetails?.deviceId,
                hasCachedSnapshot = state.deviceDetails?.snapshot != null,
                alreadyLoading = state.deviceDetails?.loading == true
            )
        ) {
            devicesViewModel.requestDeviceDetails(connected!!.deviceId, summaryOnly = true)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .deviceFileDropTarget(onHoverChange = { dropHover = it }, onFilesDropped = onFilesDropped)
            .then(
                if (dropHover) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                } else {
                    Modifier
                }
            )
    ) {
        val showDropHint = currentPlatformLabel() != "Android" && rows.isEmpty() && !isDemoActive
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = if (showDropHint) 44.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isDemoActive) {
                DemoModeBanner(
                    onExitDemo = { DemoModeState.exitDemo() }
                )
            }
            if (isInitialLoadComplete && !postPairBannerDismissed && !isDemoActive) {
                PostPairBackgroundSetupBanner(
                    pairedCount = rows.size,
                    onboardingSteps = onboardingSteps,
                    onReviewSetup = onOpenBackgroundSetup,
                    onDismiss = { postPairBannerDismissed = true }
                )
            }

            when (SimpleOverviewContentPolicy.resolve(connected != null, isInitialLoadComplete)) {
                SimpleOverviewBodyState.Loading -> {
                    Spacer(modifier = Modifier.fillMaxWidth())
                }
                SimpleOverviewBodyState.Welcome -> {
                    SimpleWelcomeOverview(
                        onAddDevice = onAddDevice,
                        onJoinDevice = onJoinDevice,
                        onLaunchDemo = { DemoModeState.launchDemo() },
                        onShareAsLink = linkFilePicker,
                        onOpenBulletinBoard = onOpenBulletinBoard
                    )
                }
                SimpleOverviewBodyState.Connected -> {
                    val activeDevice = connected!!
                    var clipboardRefreshNonce by remember(activeDevice.deviceId) { mutableStateOf(0) }
                    val (clipEnabled, clipHint, triggerClipboardPrompt) = rememberClipboardCardState(
                        device = activeDevice,
                        refreshNonce = clipboardRefreshNonce
                    )
                    val reachedVia = reachedViaMap[activeDevice.deviceId]
                    val irohPath = remember(activeDevice.deviceId, reachedVia) {
                        IrohRuntime.livePath(activeDevice.deviceId)
                    }
                    SimpleConnectedOverview(
                        active = activeDevice,
                        snapshot = details?.snapshot,
                        reachedVia = reachedVia,
                        irohPath = irohPath,
                        isRefreshingStatus = details?.loading == true,
                        dropHover = dropHover,
                        clipboardSharingEnabled = clipEnabled,
                        clipboardPromptHint = clipHint,
                        onRefreshStatus = {
                            clipboardRefreshNonce++
                            devicesViewModel.requestDeviceDetails(activeDevice.deviceId, summaryOnly = false)
                        },
                        onChooseFilesToSend = sendFilePicker,
                        onBrowseDevice = onBrowseDevice,
                        onOpenSplitView = onOpenSplitView,
                        onShareAsLink = linkFilePicker,
                        onClipboardAction = triggerClipboardPrompt,
                        onLocatePhone = onLocateDevice,
                        showDevicesCard = showDevicesCard,
                        onDevicesAnchor = onDevicesAnchor,
                        onOpenDevices = onOpenDevices,
                        notificationContent = { SimpleNotificationSection(activeDevice) },
                        onOpenDeviceDetails = onOpenDeviceDetails
                    )
                }
            }
        }
        if (showDropHint) {
            Text(
                stringRes(if (maxWidth >= 480.dp) "simple_drop_page_full" else "simple_drop_page_short"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
            )
        }
    }
}

private object SimpleOverviewClipboardCache {
    val values = mutableMapOf<String, Boolean?>()
}

@Composable
private fun rememberClipboardCardState(
    device: DeviceListRow,
    refreshNonce: Int = 0
): Triple<Boolean?, String?, () -> Unit> {
    val scope = rememberCoroutineScope()
    var sharingEnabled by remember(device.deviceId) {
        mutableStateOf(SimpleOverviewClipboardCache.values[device.deviceId])
    }
    var promptSent by remember(device.deviceId) { mutableStateOf(false) }
    var note by remember(device.deviceId) { mutableStateOf<String?>(null) }
    val sentText = stringRes("simple_clipboard_prompt_sent")
    val failedText = stringRes("simple_prompt_failed")
    val isDemo = DemoModeState.isDemoDeviceId(device.deviceId)

    LaunchedEffect(device.deviceId, device.online, promptSent, refreshNonce) {
        if (isDemo) {
            sharingEnabled = true
            return@LaunchedEffect
        }
        if (!device.online) return@LaunchedEffect
        val cached = SimpleOverviewClipboardCache.values[device.deviceId]
        if (refreshNonce == 0 && !promptSent && cached != null) {
            sharingEnabled = cached
            return@LaunchedEffect
        }
        val entity = FileApexServices.deviceRepository.getDevice(device.deviceId) ?: return@LaunchedEffect
        val endpoint = FileApexServices.presenceMonitor.endpointIfOnline(entity) ?: return@LaunchedEffect
        do {
            val fetched = cancellableCatching {
                FileApexServices.client.clipboard.getClipboardStatus(endpoint.host, endpoint.port).sharingEnabled
            }.getOrNull()
            sharingEnabled = fetched
            if (fetched != null) {
                SimpleOverviewClipboardCache.values[device.deviceId] = fetched
            }
            if (fetched == true || !promptSent) break
            delay(3_000)
        } while (true)
    }

    val hint = note ?: if (sharingEnabled == false) stringRes("simple_clipboard_tap_enable") else null
    val onPrompt: () -> Unit = {
        scope.launch {
            val entity = FileApexServices.deviceRepository.getDevice(device.deviceId)
            val sent = entity != null &&
                NotificationBroadcaster.requestPhonePrompt(entity, PhoneSettingsPage.CLIPBOARD)
            promptSent = sent
            note = if (sent) sentText else failedText
        }
    }
    return Triple(sharingEnabled, hint, onPrompt)
}

@Composable
internal fun SimpleInfoLine(label: String, value: String, detail: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).padding(vertical = 6.dp) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SimpleManagementItem(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(icon, contentDescription = null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        },
        headlineContent = {
            Text(label, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
internal fun SimpleInfo(devicesViewModel: DevicesViewModel, active: DeviceListRow?) {
    val state by devicesViewModel.uiState.collectAsState()
    val preferences by FileApexServices.settings.deviceDetailsDisplayPreferences.collectAsState()
    LaunchedEffect(active?.deviceId) {
        active?.let { devicesViewModel.requestDeviceDetails(it.deviceId) }
    }
    val details = state.deviceDetails?.takeIf { it.deviceId == active?.deviceId }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(stringRes("info"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        when {
            active == null -> Text(stringRes("simple_no_device_title"))
            details == null || (details.loading && details.snapshot == null) -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
            details.snapshot == null && details.errorMessage != null -> Text(details.errorMessage, color = MaterialTheme.colorScheme.error)
            details.snapshot != null -> {
                ReachedByField(details.deviceId)
                DeviceDiagnosticsFormatter.detailRows(
                    snapshot = details.snapshot,
                    preferences = preferences
                ).forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
