package com.fileapex

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.fileapex.platform.AndroidRuntimePermissions
import com.fileapex.platform.BackgroundPersistenceGuidance
import com.fileapex.platform.FileApexAndroidBootstrap
import com.fileapex.platform.toUiState
import com.fileapex.platform.ServiceWatchdog
import com.fileapex.platform.ServiceWatchdogScheduler
import com.fileapex.platform.ShareServerPendingStart
import com.fileapex.platform.ShareServerRestartCoordinator
import android.util.Log
import com.fileapex.ui.theme.FileApexTeal
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private val PAIRING_URI_SCHEMES = setOf("fileapex", "apex", "omninode")
    }

    private var hasStoragePermission by mutableStateOf(false)
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
    private var isPreparingShare by mutableStateOf(false)
    private var sharePrepareError by mutableStateOf<String?>(null)

    /** True when this activity instance was brought up primarily for ACTION_SEND*. */
    private var openedFromShareSheet = false

    private var stageJob: Job? = null

    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        refreshPermissions()
        val notificationsGranted = results[Manifest.permission.POST_NOTIFICATIONS] == true
        if (notificationsGranted && hasStoragePermission) {
            startShareServer()
        }
    }

    private val legacyStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissions()
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchQrScanner()
    }

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        val candidates = extractQrScanCandidates(result)
        if (candidates.isEmpty()) return@registerForActivityResult
        val payload = PairingPayload.parseFirstOrNull(candidates)
        if (payload != null) {
            qrScanError = null
            scannedPayload = payload
            return@registerForActivityResult
        }
        scannedPayload = null
        qrScanError = PairingPayload.parseFailureMessage(candidates)
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
        configureVisibleSystemBars()
        refreshPermissions()
        requestRuntimePermissionsIfNeeded()
        if (hasStoragePermission) {
            startShareServer()
        }

        handleIncomingIntent(intent)

        setContent {
            App(
                hasStoragePermission = hasStoragePermission,
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
                exactAlarmWarningActive = exactAlarmWarningActive,
                onStartShareServer = ::startShareServer,
                onStopShareServer = ::stopShareServer,
                onExitApp = ::exitFileApex,
                onScanQr = ::requestScanQr,
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
                onUpdateSheetRequestConsumed = { requestShowUpdateSheet = false }
            )
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
        requestRuntimePermissionsIfNeeded()
        if (hasStoragePermission) {
            startShareServer()
        }
        com.fileapex.domain.presence.PresenceForegroundRefresh.onAppForegrounded()
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
        }
        if (intent?.getBooleanExtra(
                com.fileapex.platform.EXTRA_SHOW_UPDATE_SHEET,
                false
            ) == true
        ) {
            requestShowUpdateSheet = true
            intent.removeExtra(com.fileapex.platform.EXTRA_SHOW_UPDATE_SHEET)
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

        if (!AndroidShareIntake.isShareAction(intent)) return
        val shareIntent = intent ?: return

        val targetDeviceId = shareIntent.getStringExtra(
            com.fileapex.platform.DirectShareShortcutCoordinator.EXTRA_TARGET_DEVICE_ID
        )?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                com.fileapex.platform.DirectShareShortcutCoordinator.deviceIdFromShortcutId(
                    shareIntent.getStringExtra(
                        com.fileapex.platform.DirectShareShortcutCoordinator.EXTRA_SHORTCUT_ID
                    )
                )
            } else {
                null
            }

        val uris = AndroidShareIntake.extractStreamUris(shareIntent)
        if (uris.isEmpty()) {
            sharePrepareError = "No shared file was provided"
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
                    sharePrepareError = error.message ?: "Could not read shared file(s)"
                }
            )
        }
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
        hasStoragePermission = hasFullStorageAccess()
        persistenceSnapshot = BackgroundPersistenceGuidance.evaluate(this)
        ServiceWatchdogScheduler.syncBatteryOptimizationWarning(
            this,
            restricted = persistenceSnapshot.persistenceRestricted
        )
        val exactAvailable = ServiceWatchdogScheduler.refreshExactAlarmAvailability(this)
        exactAlarmWarningActive = !exactAvailable
    }

    private fun hasFullStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val read = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            val write = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            read && write
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
            legacyStoragePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
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

    private fun requestRuntimePermissionsIfNeeded() {
        val missing = AndroidRuntimePermissions.missingPermissions(this)
        if (missing.isNotEmpty()) {
            runtimePermissionsLauncher.launch(missing)
        }
    }

    private fun requestScanQr() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchQrScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchQrScanner() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan FileApex pairing QR")
            .setBeepEnabled(false)
            .setOrientationLocked(true)
        qrScannerLauncher.launch(options)
    }

    private fun extractQrScanCandidates(result: ScanIntentResult): List<String> {
        val collected = linkedSetOf<String>()
        result.getContents()?.let { collected.add(it) }
        result.originalIntent?.let { intent ->
            intent.getStringExtra(Intents.Scan.RESULT)?.let { collected.add(it) }
            collected.addAll(reassembleByteSegmentExtras(intent))
        }
        result.rawBytes?.let { bytes -> collected.addAll(decodeRawScanBytes(bytes)) }
        val normalized = collected
            .map { it.replace("\u0000", "").trim().trimStart('\uFEFF') }
            .filter { it.isNotEmpty() }
            .distinct()
        if (normalized.size > 1) {
            val joined = normalized.joinToString("")
            if (joined.isNotBlank()) {
                return (normalized + joined).distinct()
            }
        }
        return normalized
    }

    private fun reassembleByteSegmentExtras(intent: android.content.Intent): List<String> {
        val segments = mutableListOf<ByteArray>()
        var index = 0
        while (true) {
            val chunk = intent.getByteArrayExtra("${Intents.Scan.RESULT_BYTE_SEGMENTS_PREFIX}$index")
                ?: break
            if (chunk.isNotEmpty()) segments.add(chunk)
            index++
        }
        if (segments.isEmpty()) return emptyList()
        val combined = segments.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        return decodeRawScanBytes(combined)
    }

    private fun decodeRawScanBytes(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        return buildList {
            add(bytes.decodeToString())
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                runCatching { String(bytes, Charsets.UTF_16LE) }.getOrNull()?.let { add(it) }
            } else if (bytes.size % 2 == 0 && bytes.any { it == 0.toByte() }) {
                runCatching { String(bytes, Charsets.UTF_16LE) }.getOrNull()?.let { add(it) }
            }
        }
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
        if (!hasStoragePermission) {
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
            Log.w(TAG, "Starting share server without battery exemption — background survival may be limited")
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
                Log.w(TAG, "Share server start deferred — FGS not allowed :: ${error.message}")
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
