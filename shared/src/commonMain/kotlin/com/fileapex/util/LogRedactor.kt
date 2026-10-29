package com.fileapex.util

/**
 * Every log line passes through here before it is written: addresses keep only their last octet (see
 * [AddressRedaction]) and absolute paths to user files are replaced. Exception texts carry both, for example a Ktor
 * timeout names the full peer URL and a missing file names its whole path.
 */
object LogRedactor {
    private const val STOP = """\s"'<>|,;:)\]"""
    private val unixPath = Regex("""/(?:Users|home|storage|sdcard|mnt|Volumes)/[^$STOP]*|/data/media/[^$STOP]*""")
    private val windowsPath = Regex("""[A-Za-z]:\\[^$STOP]*""")

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        return windowsPath.replace(unixPath.replace(AddressRedaction.redact(text), "[path]"), "[path]")
    }
}
