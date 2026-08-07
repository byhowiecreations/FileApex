package com.fileapex.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fileapex.data.settings.androidAppContextOrNull

actual object PlatformClipboard {
    actual fun getSystemClipboardText(): String? {
        val context = androidAppContextOrNull() ?: return null
        return runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val item = clipboard?.primaryClip?.getItemAt(0)
            item?.text?.toString() ?: item?.uri?.toString()
        }.getOrNull()
    }

    actual fun setSystemClipboardText(text: String) {
        val context = androidAppContextOrNull() ?: return
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("FileApex", text)
            clipboard?.setPrimaryClip(clip)
        }
    }

    actual fun openUrlInDefaultBrowser(url: String) {
        val context = androidAppContextOrNull() ?: return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
