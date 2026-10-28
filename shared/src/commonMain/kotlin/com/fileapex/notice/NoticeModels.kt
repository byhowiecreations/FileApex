package com.fileapex.notice

import com.fileapex.update.FileApexSemVer

enum class NoticeSeverity { Info, Important, Required }

enum class NoticePlatform { Android, Macos, Windows, Linux, Docker }

enum class NoticeChannel { Github, Play, Fdroid }

enum class NoticeFeature { GoogleLink, Tailscale, Iroh }

/** Update opens the update path of the flavor that is running: the in-app updater on GitHub, the store listing otherwise. */
enum class NoticeAction { Update }

data class Notice(
    val id: String,
    val severity: NoticeSeverity,
    val from: FileApexSemVer?,
    val to: FileApexSemVer?,
    val below: FileApexSemVer?,
    val expiresEpochDay: Long,
    val platforms: Set<NoticePlatform>?,
    val channels: Set<NoticeChannel>?,
    val requires: Set<NoticeFeature>?,
    val title: Map<String, String>,
    val text: Map<String, String>,
    val link: String?,
    val linkPlay: String?,
    val action: NoticeAction? = null
) {
    fun titleFor(language: String): String = title[language] ?: title.getValue("en")

    fun textFor(language: String): String = text[language] ?: text.getValue("en")
}

data class NoticePayload(
    val serial: Long,
    val issuedEpochDay: Long,
    val notices: List<Notice>
)

data class NoticeContext(
    val version: FileApexSemVer?,
    val platform: NoticePlatform,
    val channel: NoticeChannel,
    val features: Set<NoticeFeature>,
    val nowEpochMs: Long
)
