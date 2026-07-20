package com.omninode

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
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
import com.omninode.domain.pairing.PairingPayload
import com.omninode.domain.share.IncomingSharePayload
import com.omninode.network.FileShareServerService
import com.omninode.platform.AndroidShareIntake
import com.omninode.platform.ServiceWatchdog
import com.omninode.platform.ServiceWatchdogScheduler
import com.omninode.platform.ShareServerPendingStart
import android.util.Log
import com.omninode.ui.theme.OmniTeal
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var hasStoragePermission by mutableStateOf(false)
    private var hasUnrestrictedBattery by mutableStateOf(false)
    private var exactAlarmWarningActive by mutableStateOf(false)
    private var scannedPayload by mutableStateOf<PairingPayload?>(null)

    private var incomingShare by mutableStateOf<IncomingSharePayload?>(null)
    private var isPreparingShare by mutableStateOf(false)
    private var sharePrepareError by mutableStateOf<String?>(null)

    /** True when this activity instance was brought up primarily for ACTION_SEND*. */
    private var openedFromShareSheet = false

    private var stageJob: Job? = null

    private val legacyStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissions()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            refreshPermissions()
            if (hasStoragePermission) {
                startShareServer()
            }
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchQrScanner()
    }

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        runCatching {
            scannedPayload = PairingPayload.parse(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val barColor = OmniTeal.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(barColor),
            navigationBarStyle = SystemBarStyle.dark(barColor)
        )
        super.onCreate(savedInstanceState)
        configureVisibleSystemBars()
        refreshPermissions()
        requestNotificationPermissionIfNeeded()
        if (hasStoragePermission) {
            startShareServer()
        }

        handleIncomingIntent(intent)

        setContent {
            App(
                hasStoragePermission = hasStoragePermission,
                hasUnrestrictedBattery = hasUnrestrictedBattery,
                onRequestStoragePermission = ::requestStoragePermission,
                onOpenStorageSettings = ::openStorageSettings,
                onRequestBatteryUnrestricted = ::requestBatteryUnrestricted,
                onOpenExactAlarmSettings = ::openExactAlarmSettings,
                onOpenAppDetailsSettings = ::openAppDetailsSettings,
                exactAlarmWarningActive = exactAlarmWarningActive,
                onStartShareServer = ::startShareServer,
                onStopShareServer = ::stopShareServer,
                onExitApp = ::exitOmniNode,
                onScanQr = ::requestScanQr,
                appVersionName = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull().orEmpty().ifBlank { com.omninode.update.OmniNodeAppVersion.NAME },
                scannedPayload = scannedPayload,
                onScannedPayloadConsumed = { scannedPayload = null },
                onPermissionRecheck = ::refreshPermissions,
                incomingShare = incomingShare,
                isPreparingShare = isPreparingShare,
                sharePrepareError = sharePrepareError,
                onIncomingShareConsumed = { incomingShare = null },
                onShareFlowFinished = ::onShareFlowFinished,
                onDismissShareError = ::onDismissShareError
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
        if (hasStoragePermission) {
            startShareServer()
        }
    }

    override fun onDestroy() {
        stageJob?.cancel()
        super.onDestroy()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (!AndroidShareIntake.isShareAction(intent)) return

        val uris = AndroidShareIntake.extractStreamUris(intent)
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
        isPreparingShare = false
        sharePrepareError = null
        if (openedFromShareSheet) {
            openedFromShareSheet = false
            // Return to the app that opened the Share sheet (or leave OmniNode home if reused).
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

    private fun exitOmniNode() {
        stopShareServer()
        finishAffinity()
    }

    private fun configureVisibleSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Honor the system display timeout — do not keep the screen on while OmniNode is open.
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
        hasUnrestrictedBattery = isBatteryUnrestricted()
        ServiceWatchdogScheduler.syncBatteryOptimizationWarning(
            this,
            restricted = !hasUnrestrictedBattery
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

    private fun isBatteryUnrestricted(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
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

    @SuppressLint("BatteryLife")
    private fun requestBatteryUnrestricted() {
        if (isBatteryUnrestricted()) {
            hasUnrestrictedBattery = true
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }
            .onFailure {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
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
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }
            .onFailure {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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
            .setPrompt("Scan OmniNode pairing QR")
            .setBeepEnabled(false)
            .setOrientationLocked(true)
        qrScannerLauncher.launch(options)
    }

    private fun startShareServer() {
        val wasPending = ShareServerPendingStart.consume(this)
        if (wasPending) {
            Log.i(TAG, "Recovering share server after background suppression")
        }
        if (!hasUnrestrictedBattery) {
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
                ShareServerPendingStart.mark(this)
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
