package com.fileapex.notice

import com.fileapex.update.FileApexSemVer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

sealed interface NoticePayloadResult {
    data class Ok(val payload: NoticePayload) : NoticePayloadResult
    data object UnknownVersion : NoticePayloadResult
    data object Invalid : NoticePayloadResult
}

object NoticePayloadParser {
    const val FORMAT_VERSION = 1L
    const val MAX_NOTICES = 20
    const val MAX_VALIDITY_DAYS = 366L
    private const val MAX_ID = 64
    private const val MAX_LANGUAGES = 8

    private val ID_PATTERN = Regex("""^[A-Za-z0-9._-]{1,$MAX_ID}$""")
    private val LANGUAGE_PATTERN = Regex("""^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$""")

    /** A broken envelope field rejects the whole payload; a broken single notice is dropped and the rest are kept. */
    fun parse(payload: String): NoticePayloadResult {
        val root = NoticeJson.parseObject(payload) ?: return NoticePayloadResult.Invalid
        val version = root.long("v") ?: return NoticePayloadResult.Invalid
        if (version != FORMAT_VERSION) return NoticePayloadResult.UnknownVersion
        val serial = root.long("serial")?.takeIf { it >= 0 } ?: return NoticePayloadResult.Invalid
        val issued = root.string("issued")?.let(NoticeDate::parseEpochDay) ?: return NoticePayloadResult.Invalid
        val list = root["notices"] as? JsonArray ?: return NoticePayloadResult.Invalid
        if (list.size > MAX_NOTICES) return NoticePayloadResult.Invalid
        val seen = HashSet<String>()
        val notices = list.mapNotNull { element -> parseNotice(element, issued) }.filter { seen.add(it.id) }
        return NoticePayloadResult.Ok(NoticePayload(serial, issued, notices))
    }

    private fun parseNotice(element: JsonElement, issuedEpochDay: Long): Notice? {
        val o = element as? JsonObject ?: return null
        val id = o.string("id")?.takeIf { ID_PATTERN.matches(it) } ?: return null
        val severity = when (o.string("severity")) {
            "info" -> NoticeSeverity.Info
            "important" -> NoticeSeverity.Important
            "required" -> NoticeSeverity.Required
            else -> return null
        }
        val from = o.version("from") ?: if (o.has("from")) return null else null
        val to = o.version("to") ?: if (o.has("to")) return null else null
        val below = o.version("below") ?: if (o.has("below")) return null else null
        if (to != null && below != null) return null
        val expires = o.string("expires")?.let(NoticeDate::parseEpochDay) ?: return null
        if (expires - issuedEpochDay > MAX_VALIDITY_DAYS) return null
        val platforms = o.enumSet("platforms") { platformOf(it) } ?: if (o.has("platforms")) return null else null
        val channels = o.enumSet("channels") { channelOf(it) } ?: if (o.has("channels")) return null else null
        val requires = o.enumSet("requires") { featureOf(it) } ?: if (o.has("requires")) return null else null
        val title = o.localized("title", NoticeText.TITLE_MAX, allowNewlines = false) ?: return null
        val text = o.localized("text", NoticeText.TEXT_MAX, allowNewlines = true) ?: return null
        return Notice(
            id = id, severity = severity, from = from, to = to, below = below, expiresEpochDay = expires,
            platforms = platforms, channels = channels, requires = requires, title = title, text = text,
            link = o.string("link")?.let(NoticeText::githubLink),
            linkPlay = o.string("link_play")?.let(NoticeText::playLink),
            action = if (o.string("action") == "update") NoticeAction.Update else null
        )
    }

    private fun JsonObject.has(key: String) = containsKey(key)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonObject.version(key: String): FileApexSemVer? = string(key)?.let(FileApexSemVer::parse)

    /** Null when the key is absent or the value is not a list; unknown names are skipped so an older app ignores newer targets. */
    private fun <T> JsonObject.enumSet(key: String, map: (String) -> T?): Set<T>? {
        val array = this[key] as? JsonArray ?: return null
        return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(map) }.toSet()
    }

    private fun JsonObject.localized(key: String, max: Int, allowNewlines: Boolean): Map<String, String>? {
        val entries = this[key] as? JsonObject ?: return null
        if (entries.size > MAX_LANGUAGES) return null
        val out = LinkedHashMap<String, String>()
        for ((language, value) in entries) {
            if (!LANGUAGE_PATTERN.matches(language)) continue
            val cleaned = NoticeText.clean((value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue, max, allowNewlines)
            if (cleaned.isNotEmpty()) out[language] = cleaned
        }
        return out.takeIf { "en" in it }
    }

    private fun platformOf(name: String) = when (name) {
        "android" -> NoticePlatform.Android
        "macos" -> NoticePlatform.Macos
        "windows" -> NoticePlatform.Windows
        "linux" -> NoticePlatform.Linux
        "docker" -> NoticePlatform.Docker
        else -> null
    }

    private fun channelOf(name: String) = when (name) {
        "github" -> NoticeChannel.Github
        "play" -> NoticeChannel.Play
        "fdroid" -> NoticeChannel.Fdroid
        else -> null
    }

    private fun featureOf(name: String) = when (name) {
        "google_link" -> NoticeFeature.GoogleLink
        "tailscale" -> NoticeFeature.Tailscale
        "iroh" -> NoticeFeature.Iroh
        else -> null
    }
}
