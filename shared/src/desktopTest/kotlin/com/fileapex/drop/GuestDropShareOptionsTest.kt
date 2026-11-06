package com.fileapex.drop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestDropShareOptionsTest {
    @Test fun everyChoiceOfTimeLimitIsInsideWhatTheRegistryAccepts() {
        assertTrue(GuestDropLimits.choices.isNotEmpty())
        GuestDropLimits.choices.forEach { assertTrue(it.ttlMs in GuestDrops.MIN_TTL_MS..GuestDrops.MAX_TTL_MS) }
    }

    @Test fun theDefaultChoiceIsOneHourAndIsOneOfTheChoices() {
        assertEquals(GuestDrops.DEFAULT_TTL_MS, GuestDropLimits.default.ttlMs)
        assertTrue(GuestDropLimits.default in GuestDropLimits.choices)
    }

    @Test fun choicesAreOrderedAndNamedByAKeyThatExistsInEveryLanguage() {
        assertEquals(GuestDropLimits.choices.sortedBy { it.ttlMs }, GuestDropLimits.choices)
        val master = File("src/commonMain/composeResources/files/i18n")
        listOf("en", "es", "zh-rCN").forEach { language ->
            val text = File(master, "$language.xml").readText()
            (GuestDropLimits.choices.map { it.labelKey } + GuestDropStrings.keys).forEach {
                assertTrue("$language lacks $it", text.contains("name=\"$it\""))
            }
        }
    }

    @Test fun everyFailureHasAMessage() {
        ShareFailure.entries.forEach { assertTrue(GuestDropStrings.keys.contains(GuestDropStrings.forFailure(it))) }
    }

    @Test fun timeLeftIsRoundedDownAndNeverNegative() {
        assertEquals("0 s", timeLeftText(-5))
        assertEquals("59 s", timeLeftText(59_999))
        assertEquals("1 min", timeLeftText(60_000))
        assertEquals("12 min", timeLeftText(12 * 60_000 + 59_000))
        assertEquals("1 h 5 min", timeLeftText(3_900_000))
        assertEquals("24 h 0 min", timeLeftText(24 * 3_600_000L))
    }

    @Test fun stoppingEverythingWorksWithNoLinksAndOutlivesTheCaller() = kotlinx.coroutines.runBlocking {
        val job = GuestDropHub.stopAll()
        kotlinx.coroutines.withTimeout(10_000L) { job.join() }
        assertTrue(GuestDropHub.service.active.value.isEmpty())
    }
}
