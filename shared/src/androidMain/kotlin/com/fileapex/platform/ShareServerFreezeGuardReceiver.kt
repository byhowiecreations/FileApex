package com.fileapex.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Dynamic receiver for screen-on, user-present, and power/connectivity transitions.
 * Re-asserts the share-server foreground service when OEM freezer policies resume the process.
 */
internal class ShareServerFreezeGuardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_POWER_CONNECTED -> {
                Log.i(TAG, "Freeze guard event action=$action")
                ShareServerKeepAliveCoordinator.reassertOrRestart(
                    context.applicationContext,
                    reason = "freeze_guard:$action"
                )
            }
        }
    }

    companion object {
        private const val TAG = "ShareServerFreezeGuard"

        fun intentFilter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
    }
}
