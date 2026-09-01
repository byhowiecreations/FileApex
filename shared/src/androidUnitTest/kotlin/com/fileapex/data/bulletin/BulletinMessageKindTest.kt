package com.fileapex.data.bulletin

import com.fileapex.data.note.NoteNotifyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulletinMessageKindTest {

    @Test
    fun batteryContentOmitsDeviceName() {
        assertEquals("The battery level is 15%.", BulletinMessageKind.batteryAlertContent(15))
        assertEquals("The battery is low.", BulletinMessageKind.batteryAlertContent(null))
    }

    @Test
    fun retractPayloadIdRoundTripsDeviceIdWithDashes() {
        val deviceId = "honor-phone-abc-123"
        val payloadId = BulletinMessageKind.retractPayloadId(deviceId, BulletinContentType.BATTERY_LOW)
        val decoded = BulletinMessageKind.decodeRetractPayloadId(payloadId)
        requireNotNull(decoded)
        assertEquals(deviceId, decoded.first)
        assertEquals(BulletinContentType.BATTERY_LOW, decoded.second)
    }

    @Test
    fun matchesRetractIncludesLegacyBatteryText() {
        assertTrue(
            BulletinMessageKind.matchesRetract(
                messageContentType = BulletinContentType.TEXT,
                messageContent = "The battery level is 15% on HONOR MBH-N49",
                retractContentType = BulletinContentType.BATTERY_LOW
            )
        )
        assertTrue(
            BulletinMessageKind.matchesRetract(
                messageContentType = BulletinContentType.BATTERY_LOW,
                messageContent = "The battery level is 15%.",
                retractContentType = BulletinContentType.BATTERY_LOW
            )
        )
        assertFalse(
            BulletinMessageKind.matchesRetract(
                messageContentType = BulletinContentType.TEXT,
                messageContent = "Hello from the office",
                retractContentType = BulletinContentType.BATTERY_LOW
            )
        )
    }

    @Test
    fun isBatteryDetectsTypedAndLegacyMessages() {
        assertTrue(
            BulletinMessageKind.isBattery(
                BulletinContentType.BATTERY_LOW,
                "The battery level is 15%."
            )
        )
        assertTrue(
            BulletinMessageKind.isBattery(
                BulletinContentType.TEXT,
                "The battery level is 15% on HONOR MBH-N49"
            )
        )
        assertFalse(
            BulletinMessageKind.isBattery(
                BulletinContentType.TEXT,
                "See you at lunch"
            )
        )
    }

    @Test
    fun newBatteryContentIsCritical() {
        assertTrue(NoteNotifyPolicy.isCriticalBulletin("The battery level is 15%."))
        assertTrue(NoteNotifyPolicy.isCriticalBulletin("The battery is low."))
    }
}
