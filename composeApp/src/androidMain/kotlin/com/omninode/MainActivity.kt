package com.omninode

import android.Manifest
import android.annotation.SuppressLint
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
import com.omninode.domain.pairing.PairingPayload
import com.omninode.network.FileShareServerService
import com.omninode.ui.theme.OmniTeal
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {

    private var hasStoragePermission by mutableStateOf(false)
    private var hasUnrestrictedBattery by mutableStateOf(false)
    private var scannedPayload by mutableStateOf<PairingPayload?>(null)

    private val legacyStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissions()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        configureVisibleSystemBars()
        refreshPermissions()
        requestNotificationPermissionIfNeeded()
        if (hasStoragePermission && hasUnrestrictedBattery) {
            startShareServer()
        }

        setContent {
            App(
                hasStoragePermission = hasStoragePermission,
                hasUnrestrictedBattery = hasUnrestrictedBattery,
                onRequestStoragePermission = ::requestStoragePermission,
                onOpenStorageSettings = ::openStorageSettings,
                onRequestBatteryUnrestricted = ::requestBatteryUnrestricted,
                onStartShareServer = ::startShareServer,
                onStopShareServer = ::stopShareServer,
                onExitApp = ::exitOmniNode,
                onScanQr = ::requestScanQr,
                appVersionName = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull().orEmpty().ifBlank { com.omninode.update.OmniNodeAppVersion.NAME },
                scannedPayload = scannedPayload,
                onScannedPayloadConsumed = { scannedPayload = null },
                onPermissionRecheck = ::refreshPermissions
            )
        }
    }

    override fun onResume() {
        super.onResume()
        configureVisibleSystemBars()
        refreshPermissions()
    }

    override fun onDestroy() {
        if (isFinishing) {
            stopShareServer()
        }
        super.onDestroy()
    }

    private fun exitOmniNode() {
        stopShareServer()
        finishAffinity()
    }

    private fun configureVisibleSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = OmniTeal.toArgb()
            window.navigationBarColor = OmniTeal.toArgb()
        }

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
        val intent = Intent(this, FileShareServerService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopShareServer() {
        stopService(Intent(this, FileShareServerService::class.java))
    }
}
