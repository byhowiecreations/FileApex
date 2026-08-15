package com.fileapex.platform

import android.content.Context
import android.util.Log
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.data.db.createFileApexDatabase
import com.fileapex.data.identity.initAndroidLocalIdentity
import com.fileapex.data.settings.initAndroidAppSettings
import com.fileapex.di.FileApexServices
import com.fileapex.update.AppUpdateCoordinator

/**
 * Android process bootstrap after credential storage is unlocked.
 *
 * [android.app.Application.onCreate] can return early during Direct Boot (storage locked). That
 * leaves a half-alive process that later receives [android.content.Intent.ACTION_USER_UNLOCKED],
 * boot auto-launch, or a UI start without re-running Application.onCreate — so identity /
 * Room / settings stay uninitialized and [FileShareServerService] crashes with
 * `Call initAndroidLocalIdentity(context) before loadLocalIdentity()`.
 *
 * Call [ensureInitialized] from Application, boot watchdog, MainActivity, and the share-server
 * service before any code that touches those dependencies. Safe to call repeatedly.
 */
object FileApexAndroidBootstrap {
    private const val TAG = "FileApexBootstrap"

    @Volatile
    private var fullyInitialized = false

    private val lock = Any()

    /** @return true when credential storage is unlocked and process init has completed. */
    fun ensureInitialized(context: Context): Boolean {
        if (fullyInitialized && FileApexServices.isDatabaseReady()) return true
        val appContext = context.applicationContext
        if (!isUserStorageUnlocked(appContext)) {
            Log.i(TAG, "skip - user storage still locked")
            return false
        }
        synchronized(lock) {
            if (fullyInitialized && FileApexServices.isDatabaseReady()) return true
            Log.i(TAG, "Running deferred Android process init")
            initAndroidAppSettings(appContext)
            AndroidNotificationChannels.migrateLegacyShareServerChannels(appContext)
            AndroidNotificationChannels.ensureShareServerChannel(appContext)
            initAndroidLocalIdentity(appContext)
            initAndroidLanConnectivity(appContext)
            initAndroidTransferReceiveNotifier(appContext)
            initAndroidNoteReceiveNotifier(appContext)
            initAndroidDriveRelayNotifier(appContext)
            initAndroidBriefToast(appContext)
            initAndroidUpdateAvailableNotifier(appContext)
            if (!FileApexServices.isDatabaseReady()) {
                FileApexServices.init(createFileApexDatabase(appContext))
            }
            initAndroidDirectShareShortcuts(appContext)
            ServiceWatchdogScheduler.syncWatchdogEnabledFromSettings(appContext)
            BootLaunchPreference.syncFromSettings()
            if (FileApexServices.settings.enableServiceWatchdog.value) {
                ShareServerKeepAliveCoordinator.registerFreezeGuardIfNeeded(appContext)
                ShareServerKeepAliveCoordinator.scheduleJobIfNeeded(appContext)
            }
            AppUpdateCoordinator.onAppLaunch()
            GoogleLinkCoordinator.onAppLaunch()
            com.fileapex.cloud.FcmTokenRegistrar.start()
            com.fileapex.cloud.drive.DriveRelayCoordinator.onAppLaunch()
            fullyInitialized = true
            Log.i(TAG, "Android process init complete")
            return true
        }
    }
}
