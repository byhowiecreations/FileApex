package com.omninode.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omninode.update.AppUpdateCoordinator

/**
 * Handles update-notification taps and action buttons.
 */
class UpdateNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            UpdateNotificationActions.ACTION_OPEN_UPDATE -> {
                AppUpdateCoordinator.requestShowUpdateSheet()
                launchMainActivity(context)
            }
            UpdateNotificationActions.ACTION_DOWNLOAD_UPDATE -> {
                AppUpdateCoordinator.downloadPendingUpdate()
            }
            UpdateNotificationActions.ACTION_SKIP_UPDATE -> {
                AppUpdateCoordinator.skipPendingUpdate()
            }
        }
    }

    private fun launchMainActivity(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launch.putExtra(EXTRA_SHOW_UPDATE_SHEET, true)
        context.startActivity(launch)
    }

    companion object {
        const val EXTRA_SHOW_UPDATE_SHEET = "com.omninode.extra.SHOW_UPDATE_SHEET"
    }
}
