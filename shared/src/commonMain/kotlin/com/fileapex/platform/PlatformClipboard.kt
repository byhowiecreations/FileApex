package com.fileapex.platform

expect object PlatformClipboard {
    fun getSystemClipboardText(): String?
    fun setSystemClipboardText(text: String)
    fun openUrlInDefaultBrowser(url: String)
}

fun isWebUrl(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    val lower = trimmed.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    return runCatching {
        val noQuery = trimmed.substringBefore('?').substringBefore('#')
        val hostPart = noQuery.substringAfter("://").substringBefore('/')
        hostPart.isNotBlank() && (hostPart.contains('.') || hostPart.lowercase() == "localhost")
    }.getOrDefault(false)
}
