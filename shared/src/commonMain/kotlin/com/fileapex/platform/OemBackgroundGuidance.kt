package com.fileapex.platform

/**
 * OEM-specific copy keys for Android background / battery setup.
 * Detection lives in [detectOemVendor]; Android reads Build.MANUFACTURER / BRAND.
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
    Honor,
    Huawei,
    Other
}

fun detectOemVendor(manufacturer: String, brand: String): OemVendor {
    val maker = manufacturer.trim()
    val label = brand.trim()
    return when {
        maker.equals("motorola", ignoreCase = true) -> OemVendor.Motorola
        maker.equals("samsung", ignoreCase = true) -> OemVendor.Samsung
        maker.equals("google", ignoreCase = true) -> OemVendor.Pixel
        maker.equals("oneplus", ignoreCase = true) ||
            label.equals("oneplus", ignoreCase = true) -> OemVendor.OnePlus
        maker.equals("oppo", ignoreCase = true) ||
            label.equals("oppo", ignoreCase = true) ||
            maker.equals("realme", ignoreCase = true) -> OemVendor.Oppo
        label.equals("poco", ignoreCase = true) -> OemVendor.Poco
        maker.equals("xiaomi", ignoreCase = true) ||
            label.equals("redmi", ignoreCase = true) ||
            label.equals("xiaomi", ignoreCase = true) -> OemVendor.Xiaomi
        maker.equals("vivo", ignoreCase = true) ||
            label.equals("iqoo", ignoreCase = true) -> OemVendor.Vivo
        maker.equals("honor", ignoreCase = true) ||
            label.equals("honor", ignoreCase = true) -> OemVendor.Honor
        maker.equals("huawei", ignoreCase = true) ||
            label.equals("huawei", ignoreCase = true) -> OemVendor.Huawei
        else -> OemVendor.Other
    }
}

data class OemBackgroundGuidance(
    val vendor: OemVendor,
    val vendorLabel: String,
    val batteryStepsKey: String,
    val autoStartHintKey: String?,
    val alwaysShowSetup: Boolean = false
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
            OemVendor.Honor -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "Honor",
                batteryStepsKey = "oem_honor_battery_steps",
                autoStartHintKey = "oem_honor_autostart",
                alwaysShowSetup = true
            )
            OemVendor.Huawei -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "Huawei",
                batteryStepsKey = "oem_huawei_battery_steps",
                autoStartHintKey = "oem_huawei_autostart",
                alwaysShowSetup = true
            )
            OemVendor.Other -> error("Other has no guidance")
        }
    }
}
