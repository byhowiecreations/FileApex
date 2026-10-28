package com.fileapex.notice

internal object NoticeDate {
    const val MS_PER_DAY = 86_400_000L

    private val PATTERN = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

    /** Days since 1970-01-01 for an ISO date, or null when the text is not a real calendar date. */
    fun parseEpochDay(raw: String): Long? {
        val match = PATTERN.matchEntire(raw) ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
        val y = if (month <= 2) year - 1 else year
        val era = y.floorDiv(400)
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }

    fun epochDayOf(epochMs: Long): Long = epochMs.floorDiv(MS_PER_DAY)

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
}
