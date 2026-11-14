package com.fileapex

import android.app.Application
import com.fileapex.platform.log.Log
import androidx.work.Configuration
import com.fileapex.platform.FileApexAndroidBootstrap
import com.fileapex.platform.ShareServerForegroundNotification
import com.fileapex.platform.isUserStorageUnlocked
import com.fileapex.platform.trimsMemoryAt

class FileApexApplication : Application(), Configuration.Provider {
    // First, before any property below can start the coroutine pool (it reads the keep-alive setting once).
    init {
        com.fileapex.platform.ThreadTuning.apply()
        com.fileapex.util.LogRedaction.install()
    }

    /** WorkManager starts the first time something schedules or cancels work, not at launch. */
    override val workManagerConfiguration: Configuration by lazy { Configuration.Builder().build() }

    override fun onCreate() {
        super.onCreate()
        com.fileapex.cloud.FirebaseGate.initIfLinkedAtLaunch(this)
        com.fileapex.di.FileApexServices.isPlayStoreBuild = BuildConfig.IS_PLAY_STORE
        com.fileapex.di.FileApexServices.isFdroidBuild = BuildConfig.IS_FDROID
        com.fileapex.di.FileApexServices.buildChannel = BuildConfig.BUILD_CHANNEL
        com.fileapex.drop.GuestDropHub.onNotice = { com.fileapex.network.GuestDropForegroundService.apply(this, it) }
        com.fileapex.i18n.LocaleChromeRefresh.listener = {
            ShareServerForegroundNotification.refreshLocalizedCopy(this)
        }
        com.fileapex.platform.ClipboardShareChrome.listener = {
            ShareServerForegroundNotification.refreshLocalizedCopy(this)
        }
        com.fileapex.widget.AndroidWidgetBridge.onRefreshAllWidgets = { ctx ->
            com.fileapex.widget.WidgetUiFormatter.updateAllWidgets(ctx)
        }
        com.fileapex.widget.AndroidWidgetBridge.onRefreshBulletinWidgets = { ctx ->
            com.fileapex.widget.WidgetUiFormatter.updateBulletinWidgets(ctx)
        }
        com.fileapex.widget.AndroidWidgetBridge.onOpenMainActivityDestination = { ctx, dest ->
            val launchIntent = android.content.Intent(ctx, MainActivity::class.java).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                when (dest) {
                    com.fileapex.widget.WidgetDeepLinks.MainDestination.BulletinBoard ->
                        putExtra(
                            com.fileapex.widget.WidgetDeepLinks.EXTRA_WIDGET_ROUTE,
                            com.fileapex.widget.WidgetDeepLinks.ROUTE_BULLETIN
                        )
                    com.fileapex.widget.WidgetDeepLinks.MainDestination.Friends ->
                        putExtra(
                            com.fileapex.widget.WidgetDeepLinks.EXTRA_WIDGET_ROUTE,
                            com.fileapex.widget.WidgetDeepLinks.ROUTE_FRIENDS
                        )
                    is com.fileapex.widget.WidgetDeepLinks.MainDestination.BrowseDevice -> {
                        putExtra(
                            com.fileapex.widget.WidgetDeepLinks.EXTRA_WIDGET_ROUTE,
                            com.fileapex.widget.WidgetDeepLinks.ROUTE_BROWSE_DEVICE
                        )
                        putExtra(
                            com.fileapex.platform.DirectShareShortcutCoordinator.EXTRA_TARGET_DEVICE_ID,
                            dest.deviceId
                        )
                    }
                }
            }
            ctx.startActivity(launchIntent)
        }
        if (!isUserStorageUnlocked(this)) {
            // Pre-unlock process start (should be rare now that LOCKED_BOOT_COMPLETED is not
            // registered). Credential-encrypted storage throws until unlock, skip full init.
            // [FileApexAndroidBootstrap.ensureInitialized] completes init later from
            // USER_UNLOCKED / BOOT_COMPLETED / MainActivity / FileShareServerService.
            Log.i(TAG, "onCreate: user storage still locked - deferring full init")
            return
        }
        FileApexAndroidBootstrap.ensureInitialized(this)
        com.fileapex.platform.Diagnostics.log("Application initialized (channel=${BuildConfig.BUILD_CHANNEL}, play=${BuildConfig.IS_PLAY_STORE})")
        com.fileapex.platform.ProcessExitLogger.record(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (trimsMemoryAt(level) && isUserStorageUnlocked(this)) {
            com.fileapex.platform.MemoryTrim.release()
        }
    }

    private companion object {
        private const val TAG = "FileApexApplication"
    }
}
