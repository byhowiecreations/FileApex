package com.fileapex.platform

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

class ClipboardActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val text = intent.getStringExtra(EXTRA_CLIPBOARD_TEXT) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId != -1) {
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(CLIPBOARD_NOTIFICATION_TAG, notificationId)
        }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("FileApex", text)
        ClipboardShareSuppressor.isApplyingRemote = true
        try {
            clipboard?.setPrimaryClip(clip)
        } finally {
            ClipboardShareSuppressor.isApplyingRemote = false
        }
        BriefToast.show(com.fileapex.i18n.AppI18n.t("copied_to_clipboard"))
    }

    companion object {
        const val ACTION_CLIPBOARD_COPY = "com.fileapex.action.CLIPBOARD_COPY"
        const val EXTRA_CLIPBOARD_TEXT = "com.fileapex.extra.CLIPBOARD_TEXT"
        const val EXTRA_NOTIFICATION_ID = "com.fileapex.extra.NOTIFICATION_ID"
        const val CLIPBOARD_NOTIFICATION_TAG = "fileapex.clipboard"
    }
}
