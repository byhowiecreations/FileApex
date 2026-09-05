package com.fileapex

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.fileapex.domain.pairing.PairingPayload
import com.fileapex.domain.share.IncomingSharePayload
import com.fileapex.network.FileShareServerService
import com.fileapex.platform.AndroidShareIntake
import com.fileapex.platform.DirectShareShortcutCoordinator
import com.fileapex.platform.AndroidOnboardingPermissions
import com.fileapex.platform.AndroidRuntimePermissions
import com.fileapex.platform.AndroidStorageAccess
import com.fileapex.platform.BackgroundPersistenceGuidance
import com.fileapex.platform.BatteryBulletinCoordinator
import com.fileapex.platform.FileApexAndroidBootstrap
import com.fileapex.platform.OnboardingPermissionStep
import com.fileapex.platform.toUiState
import com.fileapex.platform.ServiceWatchdog
import com.fileapex.platform.ServiceWatchdogScheduler
import com.fileapex.platform.ShareServerPendingStart
import com.fileapex.platform.ShareServerRestartCoordinator
import android.util.Log
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.withAppLocale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase.withAppLocale(AppI18n.locale))
    }

    companion object {
        private const val TAG = "MainActivity"
        private val PAIRING_URI_SCHEMES = setOf("fileapex", "apex", "omninode")
    }

    private var hasStoragePermission by mutableStateOf(false)
    private var onboardingSteps by mutableStateOf<List<OnboardingPermissionStep>>(emptyList())
    private var onboardingComplete by mutableStateOf(false)
    private var deniedOnboardingStepIds by mutableStateOf(setOf<String>())
    private var persistenceSnapshot by mutableStateOf(
        BackgroundPersistenceGuidance.Snapshot(
            batteryOptimizationRestricted = false,
            backgroundRestricted = false,
            unusedAppRestrictionsActive = false,
            oemGuidance = null
        )
    )
    private var exactAlarmWarningActive by mutableStateOf(false)
    private var scannedPayload by mutableStateOf<PairingPayload?>(null)
    private var qrScanError by mutableStateOf<String?>(null)

    private var incomingShare by mutableStateOf<IncomingSharePayload?>(null)
    private var directShareDeviceId by mutableStateOf<String?>(null)
    private var requestShowUpdateSheet by mutableStateOf(false)
    private var pendingOpenNoteId by mutableStateOf<String?>(null)
    private var pendingOpenBulletinBoard by mutableStateOf(false)
    private var pendingOpenDeviceId by mutableStateOf<String?>(null)
    private var isPreparingShare by mutableStateOf(false)
    private var sharePrepareError by mutableStateOf<String?>(null)

    /** True when this activity instance was brought up primarily for ACTION_SEND*. */
    private var openedFromShareSheet = false

    private var stageJob: Job? = null
    private var pendingCellularOptInProceed: (() -> Unit)? = null
    private var pendingOnboardingStepId: String? = null
    private var pendingStorageOnboardingReturn = false
    private var pendingBatteryOnboardingReturn = false

    private val legacyStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        refreshOnboardingAfterExternalReturn(
            stepId = AndroidOnboardingPermissions.ID_MANAGE_EXTERNAL_STORAGE,
            granted = results.values.all { it }
        )
    }

    private val onboardingRuntimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val stepId = pendingOnboardingStepId
        pendingOnboardingStepId = null
        if (stepId != null) {
            updateOnboardingDenial(stepId, granted)
        }
        refreshPermissions()
    }

    private val phoneStatePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingCellularOptInProceed?.invoke()
        }
        pendingCellularOptInProceed = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val barColor = FileApexTeal.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(barColor),
            navigationBarStyle = SystemBarStyle.dark(barColor)
        )
        super.onCreate(savedInstanceState)
        // Complete init if this process deferred Application.onCreate during Direct Boot.
        FileApexAndroidBootstrap.ensureInitialized(this)
        BatteryBulletinCoordinator.onProcessStart(this)
        configureVisibleSystemBars()
        refreshPermissions()
        if (onboardingComplete) {
            startShareServer()
        }

        handleIncomingIntent(intent)

        setContent {
            Box(modifier = Modifier.fillMaxSize()) {

                App(
                    hasStoragePermission = hasStoragePermission,
                    onboardingSteps = onboardingSteps,
                    onboardingComplete = onboardingComplete,
                    deniedOnboardingStepIds = deniedOnboardingStepIds,
                    onGrantOnboardingStep = ::grantOnboardingStep,
                    hasUnrestrictedBattery = !persistenceSnapshot.persistenceRestricted,
                    backgroundPersistence = persistenceSnapshot.toUiState(),
                    onRequestStoragePermission = ::requestStoragePermission,
                    onOpenStorageSettings = ::openStorageSettings,
                    onRequestBatteryUnrestricted = ::requestBatteryUnrestricted,
                    onOpenBackgroundPersistenceSettings = ::openBackgroundPersistenceSettings,
                    onOpenUnusedAppRestrictionsSettings = ::openUnusedAppRestrictionsSettings,
                    onOpenAppBatteryUsageSettings = ::openAppBatteryUsageSettings,
                    onOpenExactAlarmSettings = ::openExactAlarmSettings,
                    onOpenAppDetailsSettings = ::openAppDetailsSettings,
                    onBeforeAllowOverCellularEnabled = ::requestPhoneStateForCellularOptIn,
                    exactAlarmWarningActive = exactAlarmWarningActive,
                    onStartShareServer = ::startShareServer,
                    onStopShareServer = ::stopShareServer,
                    onExitApp = ::exitFileApex,
                    appVersionName = runCatching {
                        packageManager.getPackageInfo(packageName, 0).versionName
                    }.getOrNull().orEmpty().ifBlank { com.fileapex.update.FileApexAppVersion.NAME },
                    scannedPayload = scannedPayload,
                    onScannedPayloadConsumed = { scannedPayload = null },
                    qrScanError = qrScanError,
                    onQrScanErrorConsumed = { qrScanError = null },
                    onPermissionRecheck = ::refreshPermissions,
                    incomingShare = incomingShare,
                    isPreparingShare = isPreparingShare,
                    sharePrepareError = sharePrepareError,
                    onIncomingShareConsumed = { incomingShare = null },
                    onShareFlowFinished = ::onShareFlowFinished,
                    onDismissShareError = ::onDismissShareError,
                    directShareDeviceId = directShareDeviceId,
                    requestShowUpdateSheet = requestShowUpdateSheet,
                    onUpdateSheetRequestConsumed = { requestShowUpdateSheet = false },
                    pendingOpenNoteId = pendingOpenNoteId,
                    onOpenNoteRequestConsumed = { pendingOpenNoteId = null },
                    pendingOpenBulletinBoard = pendingOpenBulletinBoard,
                    onOpenBulletinBoardConsumed = { pendingOpenBulletinBoard = false },
                    pendingOpenDeviceId = pendingOpenDeviceId,
                    onOpenDeviceRequestConsumed = { pendingOpenDeviceId = null }
                )
                com.fileapex.platform.LiveTransferCapsuleOverlay()
            }
        }

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        configureVisibleSystemBars()
        refreshPermissions()
        completePendingOnboardingReturns()
        if (onboardingComplete) {
            startShareServer()
        }
        com.fileapex.domain.presence.PresenceForegroundRefresh.onAppForegrounded()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        com.fileapex.domain.presence.PresenceForegroundRefresh.onWindowFocusChanged(hasFocus)
    }

    override fun onStop() {
        com.fileapex.domain.presence.PresenceForegroundRefresh.onAppBackgrounded()
        super.onStop()
    }

    override fun onDestroy() {
        stageJob?.cancel()
        super.onDestroy()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(
                com.fileapex.platform.EXTRA_DOWNLOAD_UPDATE,
                false
            ) == true
        ) {
            intent.removeExtra(com.fileapex.platform.EXTRA_DOWNLOAD_UPDATE)
            if (com.fileapex.update.PlatformInstallPermission.canRequestPackageInstalls()) {
                com.fileapex.update.AppUpdateCoordinator.downloadPendingUpdate()
            } else {
                requestShowUpdateSheet = true
            }
        } else if (intent?.getBooleanExtra(
                com.fileapex.platform.EXTRA_SHOW_UPDATE_SHEET,
                false
            ) == true
        ) {
            requestShowUpdateSheet = true
            intent.removeExtra(com.fileapex.platform.EXTRA_SHOW_UPDATE_SHEET)
        }
        val openNoteId = intent?.getStringExtra(com.fileapex.platform.EXTRA_OPEN_NOTE_ID)
            ?.trim()
            .orEmpty()
        if (openNoteId.isNotEmpty()) {
            pendingOpenNoteId = openNoteId
            intent?.removeExtra(com.fileapex.platform.EXTRA_OPEN_NOTE_ID)
        }

        intent?.data?.let { uri ->
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            if (host == "pair" && scheme in PAIRING_URI_SCHEMES) {
                val candidates = listOf(
                    pairingTextFromDeepLink(uri),
                    uri.toString()
                ).distinct()
                val payload = PairingPayload.parseFirstOrNull(candidates)
                if (payload != null) {
                    qrScanError = null
                    scannedPayload = payload
                } else {
                    scannedPayload = null
                    qrScanError = PairingPayload.parseFailureMessage(candidates.first())
                }
                return
            }
        }

        if (applyLauncherShortcutIntent(intent)) return

        if (!AndroidShareIntake.isShareAction(intent)) return
        val shareIntent = intent ?: return

        val shortcutId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            shareIntent.getStringExtra(DirectShareShortcutCoordinator.EXTRA_SHORTCUT_ID)
        } else {
            null
        }
        if (DirectShareShortcutCoordinator.isBulletinShortcut(shortcutId)) {
            openedFromShareSheet = true
            isPreparingShare = false
            sharePrepareError = null
            lifecycleScope.launch {
                runCatching {
                    com.fileapex.platform.AndroidShareBulletin.ingestShareIntent(this@MainActivity, shareIntent)
                    com.fileapex.platform.BriefToast.show(com.fileapex.i18n.AppI18n.t("posted_to_bulletin"))
                }.onFailure { error ->
                    com.fileapex.platform.BriefToast.show(
                        error.message ?: "Could not post to Bulletin Board"
                    )
                }
                if (openedFromShareSheet) {
                    openedFromShareSheet = false
                    finish()
                }
            }
            return
        }

        val targetDeviceId = shareIntent.getStringExtra(
            DirectShareShortcutCoordinator.EXTRA_TARGET_DEVICE_ID
        )?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                DirectShareShortcutCoordinator.deviceIdFromShortcutId(
                    shareIntent.getStringExtra(DirectShareShortcutCoordinator.EXTRA_SHORTCUT_ID)
                )
            } else {
                null
            }

        val uris = AndroidShareIntake.extractStreamUris(shareIntent)
        if (uris.isEmpty()) {
            val sharedText = AndroidShareIntake.extractSharedText(shareIntent)
            if (!sharedText.isNullOrBlank()) {
                com.fileapex.platform.PlatformClipboard.setSystemClipboardText(sharedText)
                if (targetDeviceId != null) {
                    com.fileapex.platform.BriefToast.show(com.fileapex.i18n.AppI18n.t("sending_shared_clipboard"))
                } else {
                    com.fileapex.platform.BriefToast.show(com.fileapex.i18n.AppI18n.t("text_copied_select_device"))
                }
                openedFromShareSheet = true
                isPreparingShare = false
                sharePrepareError = null
                return
            }
            sharePrepareError = com.fileapex.i18n.AppI18n.t("no_shared_file")
            isPreparingShare = false
            openedFromShareSheet = true
            return
        }

        openedFromShareSheet = true
        sharePrepareError = null
        isPreparingShare = true
        incomingShare = null
        directShareDeviceId = targetDeviceId

        stageJob?.cancel()
        stageJob = lifecycleScope.launch {
            runCatching {
                AndroidShareIntake.stageShareUris(this@MainActivity, uris)
            }.fold(
                onSuccess = { payload ->
                    incomingShare = payload
                    isPreparingShare = false
                    sharePrepareError = null
                },
                onFailure = { error ->
                    isPreparingShare = false
                    sharePrepareError = error.message ?: com.fileapex.i18n.AppI18n.t("could_not_read_shared_files")
                }
            )
        }
    }

    private fun applyLauncherShortcutIntent(intent: Intent?): Boolean {
        val destination = DirectShareShortcutCoordinator.parseLauncherDestination(intent)
            ?: return false
        when (destination) {
            DirectShareShortcutCoordinator.LauncherDestination.BulletinBoard -> {
                pendingOpenBulletinBoard = true
                pendingOpenDeviceId = null
            }
            is DirectShareShortcutCoordinator.LauncherDestination.Device -> {
                pendingOpenBulletinBoard = false
                pendingOpenDeviceId = destination.deviceId
            }
        }
        return true
    }

    private fun onShareFlowFinished() {
        incomingShare = null
        directShareDeviceId = null
        isPreparingShare = false
        sharePrepareError = null
        if (openedFromShareSheet) {
            openedFromShareSheet = false
            // Return to the app that opened the Share sheet (or leave FileApex home if reused).
            if (!isChangingConfigurations) {
                finish()
            }
        }
    }

    private fun onDismissShareError() {
        sharePrepareError = null
        isPreparingShare = false
        if (openedFromShareSheet) {
            openedFromShareSheet = false
            finish()
        }
    }

    private fun exitFileApex() {
        stopShareServer()
        finishAffinity()
    }

    private fun configureVisibleSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Honor the system display timeout — do not keep the screen on while FileApex is open.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun refreshPermissions() {
        onboardingSteps = AndroidOnboardingPermissions.buildSteps(this)
        onboardingComplete = AndroidOnboardingPermissions.isComplete(onboardingSteps)
        hasStoragePermission = onboardingSteps
            .firstOrNull { it.id == AndroidOnboardingPermissions.ID_MANAGE_EXTERNAL_STORAGE }
            ?.granted
            ?: AndroidStorageAccess.hasFullAccess(this)
        persistenceSnapshot = BackgroundPersistenceGuidance.evaluate(this)
        ServiceWatchdogScheduler.syncBatteryOptimizationWarning(
            this,
            restricted = persistenceSnapshot.persistenceRestricted
        )
        val exactAvailable = ServiceWatchdogScheduler.refreshExactAlarmAvailability(this)
        exactAlarmWarningActive = !exactAvailable
    }

    private fun completePendingOnboardingReturns() {
        if (pendingStorageOnboardingReturn) {
            pendingStorageOnboardingReturn = false
            refreshOnboardingAfterExternalReturn(
                stepId = AndroidOnboardingPermissions.ID_MANAGE_EXTERNAL_STORAGE,
                granted = AndroidStorageAccess.hasFullAccess(this)
            )
        }
        if (pendingBatteryOnboardingReturn) {
            pendingBatteryOnboardingReturn = false
            refreshOnboardingAfterExternalReturn(
                stepId = AndroidOnboardingPermissions.ID_IGNORE_BATTERY_OPTIMIZATIONS,
                granted = !BackgroundPersistenceGuidance.isBatteryOptimizationRestricted(this)
            )
        }
    }

    private fun refreshOnboardingAfterExternalReturn(stepId: String, granted: Boolean) {
        updateOnboardingDenial(stepId, granted)
        refreshPermissions()
    }

    private fun updateOnboardingDenial(stepId: String, granted: Boolean) {
        deniedOnboardingStepIds = if (granted) {
            deniedOnboardingStepIds - stepId
        } else {
            deniedOnboardingStepIds + stepId
        }
    }

    private fun grantOnboardingStep(stepId: String) {
        when (stepId) {
            AndroidOnboardingPermissions.ID_MANAGE_EXTERNAL_STORAGE -> {
                pendingStorageOnboardingReturn = true
                requestStoragePermission()
            }
            AndroidOnboardingPermissions.ID_NEARBY_WIFI_DEVICES -> {
                pendingOnboardingStepId = stepId
                onboardingRuntimePermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            AndroidOnboardingPermissions.ID_POST_NOTIFICATIONS -> {
                pendingOnboardingStepId = stepId
                onboardingRuntimePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            AndroidOnboardingPermissions.ID_IGNORE_BATTERY_OPTIMIZATIONS -> {
                pendingBatteryOnboardingReturn = true
                requestBatteryUnrestricted()
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryUnrestricted() {
        BackgroundPersistenceGuidance.launchBatteryOptimizationRequest(this)
    }

    private fun openBackgroundPersistenceSettings() {
        BackgroundPersistenceGuidance.launchBackgroundPersistenceSettings(this, persistenceSnapshot)
    }

    private fun openAppBatteryUsageSettings() {
        BackgroundPersistenceGuidance.launchAppBatteryUsageSettings(this)
    }

    private fun openUnusedAppRestrictionsSettings() {
        BackgroundPersistenceGuidance.launchUnusedAppRestrictionsSettings(this)
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openStorageSettings()
        } else {
            val permissions = AndroidStorageAccess.runtimePermissionsToRequest()
            if (permissions.isEmpty()) {
                openStorageSettings()
            } else {
                legacyStoragePermissionLauncher.launch(permissions)
            }
        }
    }

    private fun openStorageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSettings = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                "package:$packageName".toUri()
            )
            runCatching { startActivity(appSettings) }
                .onFailure {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
        } else {
            requestStoragePermission()
        }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:$packageName")
            }
            runCatching { startActivity(intent) }
                .onFailure { openAppDetailsSettings() }
        } else {
            openAppDetailsSettings()
        }
    }

    private fun openAppDetailsSettings() {
        BackgroundPersistenceGuidance.launchAppDetailsSettings(this)
    }

    private fun requestPhoneStateForCellularOptIn(onProceed: () -> Unit) {
        if (AndroidRuntimePermissions.hasReadPhoneState(this)) {
            onProceed()
            return
        }
        pendingCellularOptInProceed = onProceed
        phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
    }

    /** Rebuild pairing URI text without Android Uri.toString() path-style drift (`fileapex:///pair`). */
    private fun pairingTextFromDeepLink(uri: Uri): String = buildString {
        append(uri.scheme?.lowercase().orEmpty())
        append("://")
        append(uri.host?.lowercase().orEmpty())
        val query = uri.encodedQuery?.takeIf { it.isNotBlank() } ?: uri.query?.takeIf { it.isNotBlank() }
        if (query != null) {
            append('?')
            append(query)
        }
    }

    private fun startShareServer() {
        if (!onboardingComplete) {
            return
        }
        val wasPending = ShareServerPendingStart.consume(this)
        val heartbeatStale = !ServiceWatchdogScheduler.isShareServerRunning(this)
        if (wasPending || heartbeatStale) {
            Log.i(
                TAG,
                "Recovering share server after suppression or stale heartbeat " +
                    "(pending=$wasPending, stale=$heartbeatStale)"
            )
        }
        if (persistenceSnapshot.persistenceRestricted) {
            Log.w(TAG, "Starting share server without battery exemption - background survival may be limited")
        }
        val intent = Intent(this, FileShareServerService::class.java).apply {
            action = FileShareServerService.ACTION_START
            putExtra(FileShareServerService.EXTRA_FROM_FOREGROUND, true)
        }
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure { error ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                error is ForegroundServiceStartNotAllowedException
            ) {
                Log.w(TAG, "Share server start deferred - FGS not allowed :: ${error.message}")
                ShareServerRestartCoordinator.deferUntilForeground(
                    this,
                    "ui_start_blocked"
                )
            } else {
                Log.e(TAG, "Share server start failed", error)
            }
        }
    }

    private fun stopShareServer() {
        ServiceWatchdog.markCleanStop()
        stopService(Intent(this, FileShareServerService::class.java))
    }
}
