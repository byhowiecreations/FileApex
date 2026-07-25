package com.fileapex

import android.app.Application
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.data.db.createFileApexDatabase
import com.fileapex.data.identity.initAndroidLocalIdentity
import com.fileapex.data.settings.initAndroidAppSettings
import com.fileapex.di.FileApexServices
import com.fileapex.platform.initAndroidLanConnectivity
import com.fileapex.platform.initAndroidBriefToast
import com.fileapex.platform.initAndroidDirectShareShortcuts
import com.fileapex.platform.initAndroidTransferReceiveNotifier
import com.fileapex.platform.initAndroidUpdateAvailableNotifier
import com.fileapex.platform.ServiceWatchdogScheduler
import com.fileapex.update.AppUpdateCoordinator

class FileApexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initAndroidAppSettings(this)
        initAndroidLocalIdentity(this)
        initAndroidLanConnectivity(this)
        initAndroidTransferReceiveNotifier(this)
        initAndroidBriefToast(this)
        initAndroidUpdateAvailableNotifier(this)
        FileApexServices.init(createFileApexDatabase(this))
        initAndroidDirectShareShortcuts(this)
        ServiceWatchdogScheduler.syncWatchdogEnabledFromSettings(this)
        AppUpdateCoordinator.onAppLaunch()
        GoogleLinkCoordinator.onAppLaunch()
        // UDP wake + share server run inside FileShareServerService (typed foreground service).
    }
}
