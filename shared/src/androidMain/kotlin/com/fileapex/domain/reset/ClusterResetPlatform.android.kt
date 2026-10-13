package com.fileapex.domain.reset

import android.content.Context
import android.content.Intent
import android.os.Process
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.platform.ServiceWatchdog
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.security.KeyStore

actual object ClusterResetPlatform {
    actual val keepsAppRunning: Boolean = false

    actual fun eraseKeystoreAndSecureStorage() {
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            val aliases = ks.aliases()
            while (aliases.hasMoreElements()) {
                ks.deleteEntry(aliases.nextElement())
            }
        }
        val context = androidAppContextOrNull() ?: return
        // The identity file in here is encrypted with a Keystore key that was just deleted.
        cancellableCatching { File(context.filesDir, "tls").deleteRecursively() }
        cancellableCatching {
            context.getSharedPreferences("fileapex_identity", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    actual fun stateResetAndRestart() {
        val context = androidAppContextOrNull() ?: return

        ServiceWatchdog.markCleanStop()
        cancellableCatching {
            val serviceIntent = Intent().apply {
                setClassName(context.packageName, "com.fileapex.network.FileShareServerService")
            }
            context.stopService(serviceIntent)
        }

        val knownPrefs = listOf(
            "fileapex_settings",
            "fileapex_google_backup",
            "fileapex_onboarding_oem",
            "fileapex_identity",
            "fileapex_device_name_peer_labels",
            "fileapex_notification_channels",
            "fileapex_clipboard_deduper",
            "battery_bulletin_prefs",
            "service_watchdog_prefs",
            "fileapex_direct_share_usage",
            "pending_updates"
        )
        for (pref in knownPrefs) {
            cancellableCatching {
                context.getSharedPreferences(pref, Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
        cancellableCatching {
            val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            if (sharedPrefsDir.isDirectory) {
                sharedPrefsDir.listFiles()?.forEach { file ->
                    val name = file.name.removeSuffix(".xml")
                    context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                    file.delete()
                }
            }
        }

        // The loop above deleted the settings file that held the "default theme decided" marker; the database
        // file still exists, so without it the next launch counts as an old install and switches to Clean.
        cancellableCatching {
            context.getSharedPreferences("fileapex_settings", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(com.fileapex.data.settings.BaseAppSettings.KEY_THEME_DEFAULT_MIGRATED, true)
                .commit()
        }

        val restartIntent = Intent().apply {
            setClassName(context.packageName, "com.fileapex.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(restartIntent)

        CoroutineScope(Dispatchers.Default).launch {
            delay(250L)
            Process.killProcess(Process.myPid())
        }
    }
}
