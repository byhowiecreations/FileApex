package com.fileapex.domain.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceNameCoordinatorTest {

    @Test
    fun acceptsAlphanumericWordsWithSpacesAndHyphens() {
        assertNull(DeviceNameCoordinator.validate("HONOR Magic8 Pro"))
        assertNull(DeviceNameCoordinator.validate("Moto-razr-2026"))
        assertNull(DeviceNameCoordinator.validate("Nimo"))
    }

    @Test
    fun rejectsEmptyAndInvalidCharacters() {
        assertEquals(
            com.fileapex.i18n.AppI18n.t("device_name_empty"),
            DeviceNameCoordinator.validate("   ")
        )
        assertEquals(
            com.fileapex.i18n.AppI18n.t("device_name_alphanumeric"),
            DeviceNameCoordinator.validate("Hello_World")
        )
        assertEquals(
            com.fileapex.i18n.AppI18n.t("device_name_alphanumeric"),
            DeviceNameCoordinator.validate("-Leading")
        )
    }
}
