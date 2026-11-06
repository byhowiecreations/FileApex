package com.fileapex.domain.device

import com.fileapex.i18n.AppI18n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceNameLiveErrorTest {
    @Test
    fun nothingTypedYetIsNotAnError() {
        assertNull(DeviceNameCoordinator.liveError(""))
        assertNull(DeviceNameCoordinator.liveError("   "))
    }

    @Test
    fun aNameWithParenthesesIsReportedWhileTyping() {
        assertEquals(AppI18n.t("device_name_alphanumeric"), DeviceNameCoordinator.liveError("Ursula (Ubuntu)"))
    }

    @Test
    fun aValidNameHasNoError() {
        assertNull(DeviceNameCoordinator.liveError("Ursula-Ubuntu"))
        assertNull(DeviceNameCoordinator.liveError("Ursula Ubuntu"))
    }

    @Test
    fun theLiveErrorIsTheSameRuleTheSaveAndTheReceiverUse() {
        for (name in listOf("Hello_World", "-Leading", "Magic v5 - 2", "Café", "A.B", "Ok Name")) {
            assertEquals(name, DeviceNameCoordinator.validate(name), DeviceNameCoordinator.liveError(name))
        }
    }
}
