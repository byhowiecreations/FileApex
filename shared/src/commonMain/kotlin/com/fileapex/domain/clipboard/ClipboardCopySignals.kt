package com.fileapex.domain.clipboard

/**
 * Copy/cut chrome across Android 9–15 locales. Keep this off AccessibilityNodeInfo so
 * payload gating stays testable; OEM copy menus often never fill ClipboardManager in background.
 */
object ClipboardCopySignals {
    const val MAX_PAYLOAD_CHARS = 32_768
    const val MAX_PAYLOAD_UTF8_BYTES = 48_000
    const val MAX_RAW_HTML_CHARS = 131_072

    sealed class Prepared {
        data class Ok(val text: String) : Prepared()
        data object Empty : Prepared()
        data object TooLarge : Prepared()
        data object NotText : Prepared()
    }

    fun selectedSlice(full: String, start: Int, end: Int): String {
        if (full.isEmpty() || start < 0 || end <= start || end > full.length) return ""
        return full.substring(start, end).trim()
    }

    fun boundedRaw(raw: String?): String? {
        if (raw == null) return null
        return if (raw.length <= MAX_PAYLOAD_CHARS + 1) raw else raw.substring(0, MAX_PAYLOAD_CHARS + 1)
    }

    fun clipHasShareableText(mimeTypes: List<String>): Boolean {
        if (mimeTypes.isEmpty()) return true
        return mimeTypes.any { mime ->
            val lower = mime.lowercase().trim()
            lower.startsWith("text/") ||
                lower == "application/xhtml+xml" ||
                lower.contains("uri-list")
        }
    }

    fun prepare(raw: String?): Prepared {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return Prepared.Empty
        if (isCopyStatusOnly(trimmed)) return Prepared.Empty
        if (COPY_LABELS.contains(normalizeLabel(trimmed))) return Prepared.Empty
        if (looksLikeBinary(trimmed)) return Prepared.NotText
        if (trimmed.length > MAX_PAYLOAD_CHARS) return Prepared.TooLarge
        if (trimmed.encodeToByteArray().size > MAX_PAYLOAD_UTF8_BYTES) return Prepared.TooLarge
        return Prepared.Ok(trimmed)
    }

    fun isUsablePayload(text: String?): Boolean = prepare(text) is Prepared.Ok

    fun usableText(text: String?): String? = (prepare(text) as? Prepared.Ok)?.text

    fun preferCachedNodeOverClipboard(windowFocused: Boolean): Boolean = !windowFocused

    fun textFromNodeEvent(
        eventTexts: List<String>?,
        sourceText: String?,
        fromIndex: Int,
        toIndex: Int,
        selectionStart: Int = -1,
        selectionEnd: Int = -1
    ): String? {
        val source = sourceText.orEmpty()
        usableText(selectedSlice(source, selectionStart, selectionEnd))?.let { return it }
        usableText(selectedSlice(source, fromIndex, toIndex))?.let { return it }
        usableText(source)?.let { return it }
        val joined = eventTexts.orEmpty().joinToString("")
        usableText(selectedSlice(joined, fromIndex, toIndex))?.let { return it }
        return usableText(joined)
    }

    fun isCopyLabel(raw: String?): Boolean {
        val normalized = normalizeLabel(raw)
        if (normalized.isEmpty() || normalized.contains("copyright") || normalized.contains("版权")) {
            return false
        }
        if (COPY_LABELS.contains(normalized)) return true
        return COPY_PREFIXES.any { normalized.startsWith(it) }
    }

    fun isCopyViewId(viewId: String?): Boolean {
        if (viewId.isNullOrBlank()) return false
        val name = viewId.substringAfter('/').lowercase()
        if (name.contains("copyright")) return false
        return name == "copy" ||
            name == "cut" ||
            name == "action_copy" ||
            name == "menu_copy" ||
            name == "copy_button" ||
            name.endsWith("_copy") ||
            name.endsWith("_cut")
    }

    // MIUI/HyperOS copy goes through Taplus, not a Copy button.
    fun isOemClipboardOverlay(packageName: String?, className: String?): Boolean {
        val pkg = packageName.orEmpty()
        val cls = className.orEmpty().lowercase()
        if (pkg == "com.miui.contentcatcher") return true
        if (pkg != "com.miui.contentextension") return false
        if (cls.contains("taplussplash")) return true
        if (cls.contains("taplus") && cls.contains("float")) return true
        return cls.contains("clipboard")
    }

    fun isCopyStatusOnly(text: String): Boolean {
        val lower = normalizeLabel(text)
        if (lower.isEmpty()) return false
        if (COPY_STATUS.contains(lower)) return true
        return COPY_STATUS_PREFIXES.any { lower.startsWith(it) }
    }

    private fun looksLikeBinary(text: String): Boolean {
        if (text.indexOf('\u0000') >= 0) return true
        var controls = 0
        for (ch in text) {
            val code = ch.code
            if (code < 32 && ch != '\n' && ch != '\r' && ch != '\t') {
                controls++
                if (controls > 16) return true
            }
        }
        return false
    }

    private fun normalizeLabel(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim()
            .replace('\u00a0', ' ')
            .lowercase()
            .trimEnd('.', '。', '…', '!', '！')
            .trim()
    }

    private val COPY_PREFIXES = listOf(
        "copy ",
        "cut ",
        "copiar ",
        "cortar ",
        "复制",
        "拷贝",
        "剪切"
    )

    private val COPY_STATUS_PREFIXES = listOf(
        "copied to clipboard",
        "copied to the clipboard",
        "copiado al portapapeles",
        "se copió al portapapeles",
        "已复制到剪贴板",
        "已复制到剪贴簿"
    )

    private val COPY_STATUS = setOf(
        "copied",
        "copied to clipboard",
        "copiado",
        "copiado al portapapeles",
        "已复制",
        "已复制到剪贴板"
    )

    private val COPY_LABELS = setOf(
        "copy",
        "cut",
        "copy link",
        "copy text",
        "copy url",
        "copy address",
        "copy email",
        "copy image",
        "copy to clipboard",
        "copy link address",
        "copiar",
        "cortar",
        "copiar enlace",
        "copiar vínculo",
        "copiar texto",
        "copiar url",
        "copiar dirección",
        "copiar al portapapeles",
        "复制",
        "拷贝",
        "剪切",
        "剪贴",
        "复制链接",
        "复制连结",
        "复制文本",
        "复制文字",
        "复制网址",
        "复制地址",
        "复制到剪贴板"
    )
}
