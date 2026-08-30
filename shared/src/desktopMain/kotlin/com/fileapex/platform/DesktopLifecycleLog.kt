package com.fileapex.platform

import java.time.Instant

object DesktopLifecycleLog {
    private const val LOG_NAME = "desktop-lifecycle.log"
    private const val MAX_BYTES = 512 * 1024

    fun log(message: String) {
        runCatching {
            val file = DesktopPlatformPaths.applicationSupportDirectory().resolve(LOG_NAME)
            if (file.isFile && file.length() > MAX_BYTES) {
                val tail = file.readLines().takeLast(200).joinToString("\n")
                file.writeText(tail)
            }
            file.appendText("${Instant.now()} $message\n")
        }
    }
}
