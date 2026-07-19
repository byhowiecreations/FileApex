package com.omninode

import android.app.Application
import android.content.Intent
import com.omninode.data.db.createOmniNodeDatabase
import com.omninode.data.identity.initAndroidLocalIdentity
import com.omninode.data.settings.initAndroidAppSettings
import com.omninode.di.OmniNodeServices
import com.omninode.platform.OmniNodeWakeService
import com.omninode.platform.initAndroidTransferReceiveNotifier
import com.omninode.update.AppUpdateCoordinator

class OmniNodeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initAndroidAppSettings(this)
        initAndroidLocalIdentity(this)
        initAndroidTransferReceiveNotifier(this)
        OmniNodeServices.init(createOmniNodeDatabase(this))
        AppUpdateCoordinator.onAppLaunch()
        startService(Intent(this, OmniNodeWakeService::class.java))
    }
}
