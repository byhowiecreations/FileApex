package com.fileapex.platform

expect object PlatformClipboard {
    fun getSystemClipboardText(): String?
    fun setSystemClipboardText(text: String)
    fun openUrlInDefaultBrowser(url: String)
}

private val webUrlInTextPattern = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")

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

fun webUrlMatchesInText(text: String): List<MatchResult> =
    webUrlInTextPattern.findAll(text).toList()

fun normalizeWebUrlToken(raw: String): String =
    raw.trimEnd('.', ',', ';', ')', ']', '"', '\'')

fun primaryWebUrlInText(text: String): String? {
    val trimmed = text.trim()
    if (isWebUrl(trimmed)) return trimmed
    trimmed.lineSequence()
        .map { it.trim() }
        .firstOrNull { isWebUrl(it) }
        ?.let { return it }
    return webUrlInTextPattern.find(trimmed)?.value
        ?.let(::normalizeWebUrlToken)
        ?.takeIf { isWebUrl(it) }
}

fun textContainsWebUrl(text: String): Boolean = primaryWebUrlInText(text) != null
