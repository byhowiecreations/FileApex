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
        println("UpdatePackageReplacedReceiver: package replaced - relaunching FileApex")
        val lastNoteId = PendingUpdateStore.getLastAttemptedNoteId()
        val lastTxId = PendingUpdateStore.getLastAttemptedTransactionId()
        val offer = PendingUpdateStore.load()
        val noteId = lastNoteId.ifBlank { offer?.originNoteId.orEmpty() }
        if (noteId.isNotBlank()) {
            PendingUpdateStore.setNoteInstallStatus(noteId, "INSTALLED")
            PendingUpdateStore.setLastAttemptedNoteId("")
            println("UpdatePackageReplacedReceiver: noteId=$noteId status set to INSTALLED")
        }
        val txId = lastTxId.ifBlank { offer?.transactionId.orEmpty() }
        if (txId.isNotBlank()) {
            PendingUpdateStore.deleteUpdateApkAndCompleteTransaction(txId)
            println("UpdatePackageReplacedReceiver: txId=$txId deleted update APK and marked INSTALLED")
        } else {
            PendingUpdateStore.setLastAttemptedTransactionId("")
            PendingUpdateStore.save(null)
        }
        com.fileapex.platform.dismissAppUpdateNotification()

        com.fileapex.platform.ShareServerPendingStart.consume(context)
        val serverIntent = Intent(context, com.fileapex.network.FileShareServerService::class.java).apply {
            setAction(com.fileapex.network.FileShareServerService.ACTION_START)
            putExtra(com.fileapex.network.FileShareServerService.EXTRA_FROM_FOREGROUND, true)
        }
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(context, serverIntent)
            println("UpdatePackageReplacedReceiver: started FileShareServerService after update")
        }

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(launch) }
    }
}
