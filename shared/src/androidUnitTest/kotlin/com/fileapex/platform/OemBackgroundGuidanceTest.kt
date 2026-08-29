package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OemBackgroundGuidanceTest {

    @Test
    fun honorManufacturerAndBrandMapToHonor() {
        assertEquals(OemVendor.Honor, detectOemVendor("HONOR", "honor"))
        assertEquals(OemVendor.Honor, detectOemVendor("Honor", ""))
        assertEquals(OemVendor.Honor, detectOemVendor("", "HONOR"))
    }

    @Test
    fun honorBrandWinsWhenManufacturerIsHuawei() {
        assertEquals(OemVendor.Honor, detectOemVendor("HUAWEI", "honor"))
    }

    @Test
    fun huaweiManufacturerMapsToHuawei() {
        assertEquals(OemVendor.Huawei, detectOemVendor("HUAWEI", "huawei"))
        assertEquals(OemVendor.Huawei, detectOemVendor("Huawei", ""))
    }

    @Test
    fun existingVendorsStillResolve() {
        assertEquals(OemVendor.Samsung, detectOemVendor("samsung", "samsung"))
        assertEquals(OemVendor.Xiaomi, detectOemVendor("Xiaomi", "Redmi"))
        assertEquals(OemVendor.Other, detectOemVendor("Fairphone", "Fairphone"))
    }

    @Test
    fun honorAndHuaweiGuidanceAlwaysShowsSetup() {
        val honor = OemBackgroundGuidance.forVendor(OemVendor.Honor)
        val huawei = OemBackgroundGuidance.forVendor(OemVendor.Huawei)
        assertNotNull(honor)
        assertNotNull(huawei)
        assertTrue(honor!!.alwaysShowSetup)
        assertTrue(huawei!!.alwaysShowSetup)
        assertEquals("oem_honor_battery_steps", honor.batteryStepsKey)
        assertEquals("oem_huawei_battery_steps", huawei.batteryStepsKey)
        assertNull(OemBackgroundGuidance.forVendor(OemVendor.Other))
        assertFalse(OemBackgroundGuidance.forVendor(OemVendor.Samsung)!!.alwaysShowSetup)
    }

    @Test
    fun honorShowsOemSetupWhenAospBatteryIsAlreadyUnrestricted() {
        val state = BackgroundPersistenceUiState(
            batteryOptimizationRestricted = false,
            backgroundRestricted = false,
            unusedAppRestrictionsActive = false,
            oemGuidance = OemBackgroundGuidance.forVendor(OemVendor.Honor)
        )
        assertFalse(state.persistenceRestricted)
        assertTrue(state.showOemSetup)
    }
}
