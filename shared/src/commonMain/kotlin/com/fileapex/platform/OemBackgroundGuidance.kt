package com.fileapex.platform

/**
 * OEM-specific copy keys for Android background / battery setup.
 * Detection lives in [BackgroundPersistenceGuidance] (androidMain).
 * Step text is resolved via AppI18n at display time.
 */
enum class OemVendor {
    Motorola,
    Samsung,
    Pixel,
    Oppo,
    OnePlus,
    Xiaomi,
    Poco,
    Vivo,
    Other
}

data class OemBackgroundGuidance(
    val vendor: OemVendor,
    val vendorLabel: String,
    val batteryStepsKey: String,
    val autoStartHintKey: String?
) {
    companion object {
        fun forVendor(vendor: OemVendor): OemBackgroundGuidance? = when (vendor) {
            OemVendor.Other -> null
            else -> guidanceFor(vendor)
        }

        private fun guidanceFor(vendor: OemVendor): OemBackgroundGuidance = when (vendor) {
            OemVendor.Motorola -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "Motorola",
                batteryStepsKey = "oem_motorola_battery_steps",
                autoStartHintKey = "oem_motorola_autostart"
            )
            OemVendor.Samsung -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "Samsung",
                batteryStepsKey = "oem_samsung_battery_steps",
                autoStartHintKey = "oem_samsung_autostart"
            )
            OemVendor.Pixel -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "Pixel",
                batteryStepsKey = "oem_pixel_battery_steps",
                autoStartHintKey = null
            )
            OemVendor.Oppo -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "Oppo",
                batteryStepsKey = "oem_oppo_battery_steps",
                autoStartHintKey = "oem_oppo_autostart"
            )
            OemVendor.OnePlus -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "OnePlus",
                batteryStepsKey = "oem_oneplus_battery_steps",
                autoStartHintKey = "oem_oneplus_autostart"
            )
            OemVendor.Xiaomi -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "Xiaomi",
                batteryStepsKey = "oem_xiaomi_battery_steps",
                autoStartHintKey = "oem_xiaomi_autostart"
            )
            OemVendor.Poco -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "Poco",
                batteryStepsKey = "oem_xiaomi_battery_steps",
                autoStartHintKey = "oem_xiaomi_autostart"
            )
            OemVendor.Vivo -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "Vivo",
                batteryStepsKey = "oem_vivo_battery_steps",
                autoStartHintKey = "oem_vivo_autostart"
            )
            OemVendor.Other -> error("Other has no guidance")
        }
    }
}
