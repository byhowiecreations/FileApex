package com.fileapex.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.domain.clipboard.ClipboardCopySignals

actual object PlatformClipboard {
    actual fun getSystemClipboardText(): String? {
        val context = androidAppContextOrNull() ?: return null
        return readClipboardText(context)
    }

    actual fun getSystemClipboardTimestamp(): Long? {
        val context = androidAppContextOrNull() ?: return null
        return runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@runCatching null
            val clip = clipboard.primaryClip ?: return@runCatching null
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                clip.description?.timestamp?.takeIf { it > 0L }
            } else {
                null
            }
        }.getOrNull()
    }

    fun readClipboardText(context: Context): String? {
        val focused = ClipboardChangeMonitor.hasWindowFocus()
        if (focused) {
            readLocalClipboardText(context)?.let { return it }
        }
        ClipboardShizukuAccess.tryReadText()?.let { return it }
        return readLocalClipboardText(context)
    }

    private fun readLocalClipboardText(context: Context): String? {
        return runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@runCatching null
            val clip = clipboard.primaryClip ?: return@runCatching null
            if (clip.itemCount <= 0) return@runCatching null
            val description = clip.description
            val mimes = if (description == null) {
                emptyList()
            } else {
                (0 until description.mimeTypeCount).map { description.getMimeType(it) }
            }
            if (!ClipboardCopySignals.clipHasShareableText(mimes)) return@runCatching null
            val item = clip.getItemAt(0)
            val direct = ClipboardCopySignals.boundedRaw(item.text?.toString())
            if (!direct.isNullOrBlank()) return@runCatching direct
            val html = item.htmlText
            if (html != null && html.length > ClipboardCopySignals.MAX_RAW_HTML_CHARS) {
                return@runCatching null
            }
            val coerced = ClipboardCopySignals.boundedRaw(item.coerceToText(context)?.toString())
            if (!coerced.isNullOrBlank()) return@runCatching coerced
            item.uri?.toString()?.takeIf { it.startsWith("http") }
        }.getOrNull()
    }

    actual fun setSystemClipboardText(text: String) {
        val context = androidAppContextOrNull() ?: return
        val wrote = runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("FileApex", text)
            clipboard?.setPrimaryClip(clip)
            true
        }.getOrDefault(false)
        if (!wrote) ClipboardShizukuAccess.tryWriteText(text)
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
