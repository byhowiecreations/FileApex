package com.fileapex.notice

import com.fileapex.update.FileApexSemVer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeVersionRangeTest {
    private fun v(raw: String) = FileApexSemVer.parse(raw)

    private fun inside(version: String?, from: String? = null, to: String? = null, below: String? = null) =
        NoticeVersionRange.contains(version?.let(::v), from?.let(::v), to?.let(::v), below?.let(::v))

    @Test
    fun bothBoundsAreInclusiveAndLettersOrderBetweenReleases() {
        assertTrue(inside("0.19.1a", from = "0.19.1a", to = "0.19.6"))
        assertTrue(inside("0.19.2", from = "0.19.1a", to = "0.19.6"))
        assertTrue(inside("0.19.6", from = "0.19.1a", to = "0.19.6"))
        assertFalse(inside("0.19.1", from = "0.19.1a", to = "0.19.6"))
        assertFalse(inside("0.19.6a", from = "0.19.1a", to = "0.19.6"))
    }

    @Test
    fun belowIsExclusive() {
        assertTrue(inside("0.19.9a", from = "0.19.9", below = "0.20.0"))
        assertFalse(inside("0.20.0", from = "0.19.9", below = "0.20.0"))
        assertFalse(inside("0.19.9", below = "0.19.9"))
    }

    @Test
    fun openEndedRangesAndEveryVersion() {
        assertTrue(inside("0.1.0", to = "0.19.6"))
        assertFalse(inside("0.19.7", to = "0.19.6"))
        assertTrue(inside("3.0.0", from = "0.19.9"))
        assertFalse(inside("0.19.8b", from = "0.19.9"))
        assertTrue(inside("0.0.1"))
    }

    @Test
    fun anUnparseableRunningVersionMatchesNothing() {
        assertFalse(inside(null))
        assertFalse(inside(null, from = "0.1.0"))
    }

    @Test
    fun theTwoUserExamples() {
        val first = { version: String -> inside(version, from = "0.17.1", to = "0.18.1") }
        val second = { version: String -> inside(version, from = "0.19.1a", to = "0.19.6") }
        assertTrue(first("0.17.6") && !second("0.17.6"))
        assertTrue(!first("0.19.8b") && !second("0.19.8b"))
    }
}
