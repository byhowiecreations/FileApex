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
        return readClipboardText(context)
    }

    fun readClipboardText(context: Context): String? {
        return runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@runCatching null
            val clip = clipboard.primaryClip ?: return@runCatching null
            if (clip.itemCount <= 0) return@runCatching null
            val item = clip.getItemAt(0)
            val coerced = item.coerceToText(context)?.toString()?.trim()
            if (!coerced.isNullOrBlank()) return@runCatching coerced
            item.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: item.uri?.toString()?.takeIf { it.isNotBlank() }
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

    actual fun applyRemoteText(text: String) {
        ClipboardShareSuppressor.isApplyingRemote = true
        try {
            setSystemClipboardText(text)
        } finally {
            ClipboardShareSuppressor.isApplyingRemote = false
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
