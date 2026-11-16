package com.fileapex.platform

/** Understands the links people hand between devices: map links become a `geo:` target, video links keep their playback position. */
object RichHandoff {
    /** A place from a map link. A link with only a search text has no coordinates and carries the text as [label]. */
    data class MapTarget(val latitude: Double?, val longitude: Double?, val label: String?)

    /** A position shorter than this is not worth carrying into a link. */
    private const val MIN_POSITION_SECONDS = 5

    private val urlPattern = Regex("""^(https?)://([^/?#]+)([^?#]*)(\?[^#]*)?(#.*)?$""", RegexOption.IGNORE_CASE)
    private val atCoordinates = Regex("""@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
    private val pairCoordinates = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$""")
    private val osmFragment = Regex("""map=\d+/(-?\d+(?:\.\d+)?)/(-?\d+(?:\.\d+)?)""")
    private val timestampPattern = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$""", RegexOption.IGNORE_CASE)

    private class Parts(val host: String, val path: String, val query: List<String>, val fragment: String?) {
        fun param(name: String): String? =
            query.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")?.let(::decode)

        fun withoutParams(vararg names: String): List<String> = query.filter { it.substringBefore('=') !in names }

        fun url(query: List<String> = this.query, fragment: String? = this.fragment): String =
            "https://$host$path" + (if (query.isEmpty()) "" else "?" + query.joinToString("&")) + (fragment?.let { "#$it" } ?: "")
    }

    private fun parts(url: String): Parts? {
        val match = urlPattern.matchEntire(url.trim()) ?: return null
        val (_, host, path, query, fragment) = match.destructured
        return Parts(
            host.lowercase(),
            path,
            query.removePrefix("?").split('&').filter { it.isNotEmpty() },
            fragment.removePrefix("#").takeIf { fragment.isNotEmpty() }
        )
    }

    // --- maps ---

    fun mapTarget(url: String): MapTarget? {
        val parts = parts(url) ?: return null
        val target = when {
            parts.host == "maps.google.com" || (parts.host in googleHosts && (parts.path == "/maps" || parts.path.startsWith("/maps/"))) -> google(parts)
            parts.host == "maps.apple.com" -> apple(parts)
            parts.host == "openstreetmap.org" || parts.host == "www.openstreetmap.org" -> openStreetMap(parts)
            else -> null
        } ?: return null
        if (target.latitude == null && target.label == null) return null
        return target
    }

    private val googleHosts = setOf("google.com", "www.google.com")

    private fun google(parts: Parts): MapTarget? {
        val label = Regex("""/maps/place/([^/]+)""").find(parts.path)?.groupValues?.get(1)?.let(::decode)
        val fromPath = atCoordinates.find(parts.path)?.let { coordinates(it.groupValues[1], it.groupValues[2]) }
        val fromQuery = listOf("ll", "q", "query").firstNotNullOfOrNull { name ->
            parts.param(name)?.let { value -> pairCoordinates.matchEntire(value)?.let { coordinates(it.groupValues[1], it.groupValues[2]) } }
        }
        val point = fromPath ?: fromQuery
        val text = label ?: listOf("q", "query").firstNotNullOfOrNull { parts.param(it)?.takeIf { v -> pairCoordinates.matchEntire(v) == null } }
        return if (point != null) MapTarget(point.first, point.second, label) else text?.let { MapTarget(null, null, it) }
    }

    private fun apple(parts: Parts): MapTarget? {
        val point = parts.param("ll")?.let { value -> pairCoordinates.matchEntire(value)?.let { coordinates(it.groupValues[1], it.groupValues[2]) } }
        val label = parts.param("q") ?: parts.param("address")
        return if (point != null) MapTarget(point.first, point.second, label) else label?.let { MapTarget(null, null, it) }
    }

    private fun openStreetMap(parts: Parts): MapTarget? {
        val marker = coordinates(parts.param("mlat").orEmpty(), parts.param("mlon").orEmpty())
        val view = parts.fragment?.let { osmFragment.find(it) }?.let { coordinates(it.groupValues[1], it.groupValues[2]) }
        return (marker ?: view)?.let { MapTarget(it.first, it.second, null) }
    }

    private fun coordinates(latitude: String, longitude: String): Pair<Double, Double>? {
        val lat = latitude.toDoubleOrNull() ?: return null
        val lon = longitude.toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    /** The `geo:` URI that opens [target] in the phone's own maps app. */
    fun geoUri(target: MapTarget): String {
        val label = target.label?.let { encode(it) }
        if (target.latitude == null || target.longitude == null) return "geo:0,0?q=${label.orEmpty()}"
        val point = "${target.latitude},${target.longitude}"
        return "geo:$point?q=$point" + (label?.let { "($it)" } ?: "")
    }

    /** What a tap on [url] should open: the `geo:` URI of a map link when the phone has an app for it, else the link with its video start time. */
    fun openTarget(url: String, canOpenGeo: Boolean): String {
        val geo = if (canOpenGeo) mapTarget(url)?.let(::geoUri) else null
        return geo ?: canonicalVideoUrl(url)
    }

    // --- videos ---

    /** Seconds into the video a link starts at, for YouTube, Vimeo and Twitch links; null when there is none. */
    fun videoTimestampSeconds(url: String): Int? {
        val parts = parts(url) ?: return null
        val site = videoSite(parts) ?: return null
        val raw = when (site) {
            VideoSite.YouTube -> parts.param("t") ?: parts.param("start")
            VideoSite.Twitch -> parts.param("t")
            VideoSite.Vimeo -> parts.fragment?.takeIf { it.startsWith("t=") }?.removePrefix("t=")
        } ?: return null
        return parseTimestamp(raw)
    }

    /** [url] with its start time written the way the site reads it; unchanged when it has none. */
    fun canonicalVideoUrl(url: String): String {
        val seconds = videoTimestampSeconds(url) ?: return url
        return withTimestamp(url, seconds) ?: url
    }

    /** [url] starting at [seconds], for a video link on a site that takes a start time; unchanged otherwise. */
    fun withVideoTimestamp(url: String, seconds: Int): String =
        if (seconds < MIN_POSITION_SECONDS) url else withTimestamp(url, seconds) ?: url

    private enum class VideoSite { YouTube, Vimeo, Twitch }

    private fun videoSite(parts: Parts): VideoSite? = when {
        parts.host in youTubeHosts && parts.path == "/watch" && parts.param("v") != null -> VideoSite.YouTube
        parts.host == "youtu.be" && parts.path.length > 1 -> VideoSite.YouTube
        parts.host == "vimeo.com" && Regex("""^/\d+$""").matches(parts.path) -> VideoSite.Vimeo
        (parts.host == "twitch.tv" || parts.host == "www.twitch.tv") && Regex("""^/videos/\d+$""").matches(parts.path) -> VideoSite.Twitch
        else -> null
    }

    private val youTubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com")

    private fun withTimestamp(url: String, seconds: Int): String? {
        val parts = parts(url) ?: return null
        return when (videoSite(parts) ?: return null) {
            VideoSite.YouTube -> parts.url(query = parts.withoutParams("t", "start") + "t=${seconds}s", fragment = parts.fragment?.takeUnless { it.startsWith("t=") })
            VideoSite.Vimeo -> parts.url(fragment = "t=${seconds}s")
            VideoSite.Twitch -> parts.url(query = parts.withoutParams("t") + "t=${hms(seconds)}")
        }
    }

    private fun parseTimestamp(value: String): Int? {
        val text = value.trim()
        text.toIntOrNull()?.let { return it.takeIf { n -> n >= 0 } }
        val match = timestampPattern.matchEntire(text) ?: return null
        if (match.groupValues.drop(1).all { it.isEmpty() }) return null
        val (hours, minutes, seconds) = match.destructured
        return (hours.toIntOrNull() ?: 0) * 3600 + (minutes.toIntOrNull() ?: 0) * 60 + (seconds.toIntOrNull() ?: 0)
    }

    private fun hms(total: Int): String {
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val seconds = total % 60
        return (if (hours > 0) "${hours}h" else "") + (if (minutes > 0) "${minutes}m" else "") + (if (seconds > 0 || total == 0) "${seconds}s" else "")
    }

    // --- percent encoding ---

    private fun decode(text: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length && text.substring(i + 1, i + 3).all { it.isLetterOrDigit() }) {
                text.substring(i + 1, i + 3).toIntOrNull(16)?.let { bytes.write(it); i += 3; continue }
            }
            if (c == '+') bytes.write(' '.code) else bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun encode(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && byte >= 0 || c in "-._~") append(c) else append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }
}
