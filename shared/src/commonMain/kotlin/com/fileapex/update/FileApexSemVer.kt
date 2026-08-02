package com.fileapex.update

/**
 * FileApex versions look like `0.0.3a` (major.minor.patch + optional letter suffix).
 * GitHub tags may include a leading `v`.
 */
data class FileApexSemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val letter: Char?
) : Comparable<FileApexSemVer> {
    override fun compareTo(other: FileApexSemVer): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        return compareLetters(letter, other.letter)
    }

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)([a-zA-Z])?$""")

        fun parse(raw: String): FileApexSemVer? {
            val match = PATTERN.matchEntire(raw.trim()) ?: return null
            return FileApexSemVer(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
                letter = match.groupValues[4].firstOrNull()?.lowercaseChar()
            )
        }

        /** Null letter sorts before any letter (0.0.3 < 0.0.3a). */
        private fun compareLetters(a: Char?, b: Char?): Int {
            if (a == null && b == null) return 0
            if (a == null) return -1
            if (b == null) return 1
            return a.compareTo(b)
        }
    }
}

fun isRemoteVersionNewer(localVersion: String, remoteTag: String): Boolean {
    val local = FileApexSemVer.parse(localVersion) ?: return false
    val remote = FileApexSemVer.parse(remoteTag) ?: return false
    return remote > local
}
