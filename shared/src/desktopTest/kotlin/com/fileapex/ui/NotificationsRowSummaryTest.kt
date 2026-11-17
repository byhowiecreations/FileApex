package com.fileapex.ui

import com.fileapex.presentation.SettingsUiState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationsRowSummaryTest {
    private val allOff = SettingsUiState(
        noticesEnabled = false,
        notesNotificationsEnabled = false,
        driveRelayNotificationsEnabled = false,
        fileTransferNotificationsEnabled = false,
        liveTransferCapsuleEnabled = false
    )

    @Test
    fun nothingOnReadsOff() {
        assertEquals(NotificationsRowSummary.Off, notificationsRowSummary(allOff))
    }

    @Test
    fun onlyCriticalNotificationsSaysSo() {
        assertEquals(NotificationsRowSummary.CriticalOnly, notificationsRowSummary(allOff.copy(noticesEnabled = true)))
    }

    @Test
    fun otherwiseItCountsTheKindsThatAreOnOutOfFour() {
        assertEquals(NotificationsRowSummary.Count(1, 4), notificationsRowSummary(allOff.copy(notesNotificationsEnabled = true)))
        assertEquals(NotificationsRowSummary.Count(1, 4), notificationsRowSummary(allOff.copy(driveRelayNotificationsEnabled = true)))
        assertEquals(NotificationsRowSummary.Count(1, 4), notificationsRowSummary(allOff.copy(fileTransferNotificationsEnabled = true)))
        assertEquals(
            NotificationsRowSummary.Count(2, 4),
            notificationsRowSummary(allOff.copy(noticesEnabled = true, notesNotificationsEnabled = true))
        )
        assertEquals(
            NotificationsRowSummary.Count(4, 4),
            notificationsRowSummary(allOff.copy(noticesEnabled = true, notesNotificationsEnabled = true, driveRelayNotificationsEnabled = true, fileTransferNotificationsEnabled = true))
        )
    }

    @Test
    fun theLiveCapsuleIsPartOfFileTransferAndIsNotCountedOnItsOwn() {
        assertEquals(NotificationsRowSummary.Off, notificationsRowSummary(allOff.copy(liveTransferCapsuleEnabled = true)))
        assertEquals(
            NotificationsRowSummary.Count(1, 4),
            notificationsRowSummary(allOff.copy(fileTransferNotificationsEnabled = true, liveTransferCapsuleEnabled = true))
        )
    }

    @Test
    fun theTwoNewTextsExistInEveryLanguage() {
        val dir = File("src/commonMain/composeResources/files/i18n")
        listOf("en.xml", "es.xml", "zh-rCN.xml").forEach { name ->
            val text = File(dir, name).readText()
            listOf("notifications_row_critical_only", "notifications_row_count").forEach {
                assertEquals("$name needs $it", true, text.contains("name=\"$it\""))
            }
        }
    }
}
