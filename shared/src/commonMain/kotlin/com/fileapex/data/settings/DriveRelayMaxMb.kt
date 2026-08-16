package com.fileapex.data.settings

/**
 * Max bytes for one Google Drive Relay send — a single file, or a selected group at once.
 */
enum class DriveRelayMaxMb(val megabytes: Int) {
    Five(5),
    TwentyFive(25),
    Fifty(50),
    Hundred(100),
    TwoFifty(250),
    FiveHundred(500);

    val bytes: Long get() = megabytes * 1024L * 1024L

    val label: String get() = "$megabytes MB"

    companion object {
        val DEFAULT: DriveRelayMaxMb = Fifty

        fun fromStorage(megabytes: Int): DriveRelayMaxMb =
            entries.firstOrNull { it.megabytes == megabytes } ?: DEFAULT
    }
}
