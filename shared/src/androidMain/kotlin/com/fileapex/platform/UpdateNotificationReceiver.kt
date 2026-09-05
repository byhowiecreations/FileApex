package com.fileapex.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fileapex.update.AppUpdateCoordinator

/**
 * Handles update-notification Skip action.
 * Open/Install use Activity PendingIntents (BAL-safe on Samsung).
 */
class UpdateNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            UpdateNotificationActions.ACTION_OPEN_UPDATE -> {
                AppUpdateCoordinator.requestShowUpdateSheet()
                launchMainActivity(context, download = false)
            }
            UpdateNotificationActions.ACTION_DOWNLOAD_UPDATE -> {
                launchMainActivity(context, download = true)
            }
            UpdateNotificationActions.ACTION_SKIP_UPDATE -> {
                AppUpdateCoordinator.skipPendingUpdate()
            }
        }
    }

    private fun launchMainActivity(context: Context, download: Boolean) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setClassName(context.packageName, "com.fileapex.MainActivity")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (download) {
            launch.putExtra(EXTRA_DOWNLOAD_UPDATE, true)
        } else {
            launch.putExtra(EXTRA_SHOW_UPDATE_SHEET, true)
        }
        runCatching { context.startActivity(launch) }
    }
}
