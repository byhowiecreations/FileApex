package com.fileapex.platform

/** The media collection a file belongs to; a trash request must name the item through its own collection. */
enum class MediaKind { IMAGE, VIDEO, AUDIO }

fun mediaKindOf(mime: String?): MediaKind? {
    val type = mime?.lowercase().orEmpty()
    return when {
        type.startsWith("image/") -> MediaKind.IMAGE
        type.startsWith("video/") -> MediaKind.VIDEO
        type.startsWith("audio/") -> MediaKind.AUDIO
        else -> null
    }
}

fun mediaKindOfName(fileName: String): MediaKind? = mediaKindOf(java.net.URLConnection.guessContentTypeFromName(fileName))
