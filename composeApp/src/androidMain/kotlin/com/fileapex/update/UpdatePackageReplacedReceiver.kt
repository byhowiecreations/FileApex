package com.fileapex.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After the system finishes replacing this package, relaunch FileApex to the foreground.
 *
 * Uses [Intent.ACTION_MY_PACKAGE_REPLACED] (the broadcast delivered to the updated app).
 * [Intent.ACTION_PACKAGE_REPLACED] is not delivered to the package that was replaced.
 */
class UpdatePackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_PACKAGE_REPLACED
        ) {
            return
        }
        if (action == Intent.ACTION_PACKAGE_REPLACED) {
            val replaced = intent.data?.schemeSpecificPart
            if (replaced != null && replaced != context.packageName) return
        }
        println("UpdatePackageReplacedReceiver: package replaced — relaunching FileApex")
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(launch)
    }
}
