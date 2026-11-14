package com.fileapex.platform

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.fileapex.domain.clipboard.ClipboardImageInbox
import com.fileapex.domain.clipboard.ClipboardImageStorage
import com.fileapex.i18n.AppI18n
import java.io.File

class ClipboardActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId != -1) {
            NotificationManagerCompat.from(context).cancel(CLIPBOARD_NOTIFICATION_TAG, notificationId)
        }
        if (intent.action == ACTION_CLIPBOARD_SAVE_IMAGE) {
            val cachedPath = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: return
            val mimeType = intent.getStringExtra(EXTRA_IMAGE_MIME) ?: "image/jpeg"
            val sender = intent.getStringExtra(EXTRA_SOURCE_DEVICE).orEmpty()
            val bytes = runCatching { File(cachedPath).takeIf { it.isFile }?.readBytes() }.getOrNull() ?: return
            val saved = ClipboardImageStorage.saveToDirectory(bytes, mimeType, sender, defaultDownloadsDir())
            if (saved != null) {
                ClipboardImageInbox.dismiss()
                BriefToast.show(AppI18n.t("clipboard_image_saved", DownloadsPaths.displayLabel()))
            }
            return
        }
        val text = intent.getStringExtra(EXTRA_CLIPBOARD_TEXT) ?: return

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("FileApex", text)
        ClipboardShareSuppressor.isApplyingRemote = true
        try {
            clipboard?.setPrimaryClip(clip)
        } finally {
            ClipboardShareSuppressor.isApplyingRemote = false
        }
        BriefToast.show(AppI18n.t("copied_to_clipboard"))
    }

    companion object {
        const val ACTION_CLIPBOARD_COPY = "com.fileapex.action.CLIPBOARD_COPY"
        const val ACTION_CLIPBOARD_SAVE_IMAGE = "com.fileapex.action.CLIPBOARD_SAVE_IMAGE"
        const val EXTRA_CLIPBOARD_TEXT = "com.fileapex.extra.CLIPBOARD_TEXT"
        const val EXTRA_IMAGE_PATH = "com.fileapex.extra.IMAGE_PATH"
        const val EXTRA_IMAGE_MIME = "com.fileapex.extra.IMAGE_MIME"
        const val EXTRA_SOURCE_DEVICE = "com.fileapex.extra.SOURCE_DEVICE"
        const val EXTRA_NOTIFICATION_ID = "com.fileapex.extra.NOTIFICATION_ID"
        const val CLIPBOARD_NOTIFICATION_TAG = "fileapex.clipboard"
    }
}
