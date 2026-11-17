package com.fileapex.data.settings

import com.fileapex.notice.MapKvStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAndGoogleSettingsTest {
    private val kv = MapKvStore()
    private fun settings() = BaseAppSettings(kv)

    @Test
    fun updateChecksStartOffDailyWithNothingSkipped() {
        val s = settings()
        assertFalse(s.checkForUpdatesEnabled.value)
        assertEquals(UpdateCheckUnit.Days, s.checkForUpdatesIntervalUnit.value)
        assertEquals(1, s.checkForUpdatesIntervalAmount.value)
        assertEquals(0L, s.lastUpdateCheckEpochMs.value)
        assertEquals("", s.skippedUpdateVersion.value)
        assertFalse(s.updateCheckPromptShown.value)
    }

    @Test
    fun updateChoicesSurviveARestartAndTheIntervalIsSanitized() {
        val first = settings()
        first.setCheckForUpdatesEnabled(true)
        first.setCheckForUpdatesInterval(UpdateCheckUnit.Days, 0)
        first.setLastUpdateCheckEpochMs(123L)
        first.setSkippedUpdateVersion("  0.22.0 ")
        first.setUpdateCheckPromptShown(true)
        val second = settings()
        assertTrue(second.checkForUpdatesEnabled.value)
        assertEquals(UpdateCheckFrequency.sanitizeAmount(UpdateCheckUnit.Days, 0), second.checkForUpdatesIntervalAmount.value)
        assertEquals(123L, second.lastUpdateCheckEpochMs.value)
        assertEquals("0.22.0", second.skippedUpdateVersion.value)
        assertTrue(second.updateCheckPromptShown.value)
    }

    @Test
    fun googleFieldsAreTrimmedAndSurviveARestart() {
        val first = settings()
        first.setGoogleAccountLinkEnabled(true)
        first.setGoogleAccountEmail(" a@example.com ")
        first.setGoogleAccountUid(" uid-1 ")
        first.setGoogleRestorePending(true)
        first.setGoogleBackupEmailHint(" b@example.com ")
        val second = settings()
        assertTrue(second.googleAccountLinkEnabled.value)
        assertEquals("a@example.com", second.googleAccountEmail.value)
        assertEquals("uid-1", second.googleAccountUid.value)
        assertTrue(second.googleRestorePending.value)
        assertEquals("b@example.com", second.googleBackupEmailHint.value)
    }

    @Test
    fun unlinkingGoogleClearsTheAccountFields() {
        val s = settings()
        s.setGoogleAccountLinkEnabled(true)
        s.setGoogleAccountEmail("a@example.com")
        s.setGoogleAccountUid("uid-1")
        s.setGoogleRestorePending(true)
        s.setGoogleBackupEmailHint("b@example.com")
        s.setGoogleAccountLinkEnabled(false)
        val again = settings()
        listOf(s, again).forEach {
            assertFalse(it.googleAccountLinkEnabled.value)
            assertEquals("", it.googleAccountEmail.value)
            assertEquals("", it.googleAccountUid.value)
            assertFalse(it.googleRestorePending.value)
            assertEquals("", it.googleBackupEmailHint.value)
        }
    }

    @Test
    fun resetToDefaultsClearsBoth() {
        val s = settings()
        s.setCheckForUpdatesEnabled(true)
        s.setGoogleAccountLinkEnabled(true)
        s.setGoogleAccountEmail("a@example.com")
        s.resetToDefaults()
        assertFalse(s.checkForUpdatesEnabled.value)
        assertFalse(s.googleAccountLinkEnabled.value)
        assertEquals("", s.googleAccountEmail.value)
    }
}
