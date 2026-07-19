package com.omninode.data.settings

/**
 * How long a peer stays unlocked for browsing after the last navigation on that device.
 * [Immediate] stays valid only until the user returns to the device list (no wall-clock idle).
 */
enum class PinIdleTimeout {
    Immediate,
    OneMinute,
    FiveMinutes,
    TenMinutes;

    val label: String
        get() = when (this) {
            Immediate -> "Immediate"
            OneMinute -> "1 Minute"
            FiveMinutes -> "5 Minutes"
            TenMinutes -> "10 Minutes"
        }

    /** Wall-clock idle window; 0 means Immediate (cleared only via [com.omninode.session.DeviceSessionManager.clearSession]). */
    fun toMillis(): Long = when (this) {
        Immediate -> 0L
        OneMinute -> 60_000L
        FiveMinutes -> 5L * 60_000L
        TenMinutes -> 10L * 60_000L
    }

    companion object {
        val DEFAULT: PinIdleTimeout = FiveMinutes

        fun fromStorage(raw: String): PinIdleTimeout {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DEFAULT
        }
    }
}
