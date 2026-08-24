package com.fileapex

import android.app.Application
import android.util.Log
import com.fileapex.platform.FileApexAndroidBootstrap
import com.fileapex.platform.ShareServerForegroundNotification
import com.fileapex.platform.isUserStorageUnlocked

class FileApexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.fileapex.i18n.LocaleChromeRefresh.listener = {
            ShareServerForegroundNotification.refreshLocalizedCopy(this)
        }
        if (!isUserStorageUnlocked(this)) {
            // Pre-unlock process start (should be rare now that LOCKED_BOOT_COMPLETED is not
            // registered). Credential-encrypted storage throws until unlock — skip full init.
            // [FileApexAndroidBootstrap.ensureInitialized] completes init later from
            // USER_UNLOCKED / BOOT_COMPLETED / MainActivity / FileShareServerService.
            Log.i(TAG, "onCreate: user storage still locked - deferring full init")
            return
        }
        FileApexAndroidBootstrap.ensureInitialized(this)
    }

    private companion object {
        private const val TAG = "FileApexApplication"
    }
}
