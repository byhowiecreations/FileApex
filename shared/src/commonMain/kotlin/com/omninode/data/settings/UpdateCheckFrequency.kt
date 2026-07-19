package com.omninode.data.settings

enum class UpdateCheckUnit {
    Hours,
    Days,
    Weeks;

    companion object {
        fun fromStorage(raw: String): UpdateCheckUnit {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: Days
        }
    }
}

object UpdateCheckFrequency {
    fun allowedAmounts(unit: UpdateCheckUnit): IntRange = when (unit) {
        UpdateCheckUnit.Hours -> 1..24
        UpdateCheckUnit.Days -> 1..30
        UpdateCheckUnit.Weeks -> 1..4
    }

    fun allowedWeekValues(): List<Int> = listOf(1, 2, 4)

    fun sanitizeAmount(unit: UpdateCheckUnit, amount: Int): Int {
        return when (unit) {
            UpdateCheckUnit.Weeks -> {
                allowedWeekValues().minByOrNull { kotlin.math.abs(it - amount) } ?: 1
            }
            else -> amount.coerceIn(allowedAmounts(unit))
        }
    }

    fun toMillis(unit: UpdateCheckUnit, amount: Int): Long {
        val safe = sanitizeAmount(unit, amount)
        return when (unit) {
            UpdateCheckUnit.Hours -> safe * 60L * 60L * 1000L
            UpdateCheckUnit.Days -> safe * 24L * 60L * 60L * 1000L
            UpdateCheckUnit.Weeks -> safe * 7L * 24L * 60L * 60L * 1000L
        }
    }

    fun label(unit: UpdateCheckUnit, amount: Int): String {
        val safe = sanitizeAmount(unit, amount)
        val unitLabel = when (unit) {
            UpdateCheckUnit.Hours -> if (safe == 1) "hour" else "hours"
            UpdateCheckUnit.Days -> if (safe == 1) "day" else "days"
            UpdateCheckUnit.Weeks -> if (safe == 1) "week" else "weeks"
        }
        return "Every $safe $unitLabel"
    }
}
