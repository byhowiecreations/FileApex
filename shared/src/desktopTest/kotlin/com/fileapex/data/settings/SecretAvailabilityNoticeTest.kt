package com.fileapex.data.settings

import java.io.File
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretAvailabilityNoticeTest {
    private val keys = listOf("notice-test-a", "notice-test-b")

    @After
    fun clean() = keys.forEach { SecretAvailability.markAvailable(it) }

    @Test
    fun theStateIsOffWhenNothingIsUnavailable() {
        assertFalse(SecretAvailability.anyUnavailable.value)
    }

    @Test
    fun theStateStaysOnWhileAnyOneSecretIsUnavailable() {
        SecretAvailability.markUnavailable(keys[0])
        SecretAvailability.markUnavailable(keys[1])
        assertTrue(SecretAvailability.anyUnavailable.value)
        SecretAvailability.markAvailable(keys[0])
        assertTrue(SecretAvailability.anyUnavailable.value)
        SecretAvailability.markAvailable(keys[1])
        assertFalse(SecretAvailability.anyUnavailable.value)
    }

    @Test
    fun theNoticeWaitsOutTheGraceBeforeItShows() = runBlocking {
        SecretAvailability.markUnavailable(keys[0])
        val early = withTimeoutOrNull(100) { SecretAvailability.noticeVisible(graceMs = 600).first { it } }
        assertNull("the notice showed inside the grace", early)
        assertEquals(true, withTimeout(5_000) { SecretAvailability.noticeVisible(graceMs = 600).first { it } })
    }

    @Test
    fun aSecretThatOpensInsideTheGraceNeverShowsTheNotice() = runBlocking {
        SecretAvailability.markUnavailable(keys[0])
        val shown = withTimeoutOrNull(900) {
            coroutineScope {
                launch { delay(100); SecretAvailability.markAvailable(keys[0]) }
                SecretAvailability.noticeVisible(graceMs = 500).first { it }
            }
        }
        assertNull("the notice showed although the secret opened inside the grace", shown)
    }

    @Test
    fun theNoticeGoesOffAtOnceWhenTheSecretOpens() = runBlocking {
        SecretAvailability.markUnavailable(keys[0])
        assertTrue(withTimeout(5_000) { SecretAvailability.noticeVisible(graceMs = 100).first { it } })
        SecretAvailability.markAvailable(keys[0])
        assertFalse(withTimeout(5_000) { SecretAvailability.noticeVisible(graceMs = 100).first { !it } })
    }

    @Test
    fun theGraceIsAtLeastAMinuteSoAStartingKeystoreNeverFlashesTheNotice() {
        assertTrue(SecretAvailability.NOTICE_GRACE_MS >= 60_000L)
    }

    @Test
    fun theNoticeTextIsCalmAndExistsInEveryLanguage() {
        val alarming = listOf("error", "failed", "failure", "lost", "corrupt", "danger", "warning", "!")
        for (language in listOf("en", "es", "zh-rCN")) {
            val xml = File("src/commonMain/composeResources/files/i18n/$language.xml").readText()
            for (key in listOf("secret_notice_title", "secret_notice_body")) {
                val text = Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
                assertTrue("$language lacks $key", !text.isNullOrBlank())
                if (language == "en") alarming.forEach { assertFalse("$key says \"$it\"", text!!.lowercase().contains(it)) }
            }
        }
    }
}
