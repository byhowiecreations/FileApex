package com.fileapex.domain.reset

import android.content.Context
import android.content.Intent
import android.os.Process
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.platform.ServiceWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.security.KeyStore

actual object ClusterResetPlatform {
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
        runCatching {
            context.getSharedPreferences("fileapex_identity", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    actual fun stateResetAndRestart() {
        val context = androidAppContextOrNull() ?: return

        ServiceWatchdog.markCleanStop()
        runCatching {
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
            runCatching {
                context.getSharedPreferences(pref, Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
        runCatching {
            val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            if (sharedPrefsDir.isDirectory) {
                sharedPrefsDir.listFiles()?.forEach { file ->
                    val name = file.name.removeSuffix(".xml")
                    context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                    file.delete()
                }
            }
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
