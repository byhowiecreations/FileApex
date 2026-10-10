package com.fileapex.domain.notifications

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * TEMPORARY test aid for tracing how notification counts change on the computer.
 * Writes metadata only (source, short key hash, package, kind, counts), never titles or text, to
 * ~/Library/Logs/FileApex-notifications.log on a Mac.
 *
 * REMEMBER: set [ENABLED] to false (or delete this file and its call sites) once testing is done.
 */
internal object NotificationDebugLog {
    const val ENABLED = true

    private val file: File? by lazy {
        if (!ENABLED) return@lazy null
        if (System.getProperty("org.gradle.test.worker") != null) return@lazy null
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return@lazy null
        File(System.getProperty("user.home"), "Library/Logs/FileApex-notifications.log")
    }

    fun short(key: String): String = Integer.toHexString(key.hashCode()).takeLast(6)

    fun log(message: String) {
        val target = file ?: return
        runCatching {
            val stamp = SimpleDateFormat("HH:mm:ss.SSS").format(Date())
            target.appendText("$stamp $message\n")
        }
    }

    fun summary(items: List<NotificationPayload>): String =
        "n=${items.size} emails=${items.count { it.kind == NotificationKind.EMAIL }} " +
            items.joinToString(" ") { "${short(it.sourceDeviceId)}:${short(it.key)}/${it.kind.name.take(1)}" }
}
