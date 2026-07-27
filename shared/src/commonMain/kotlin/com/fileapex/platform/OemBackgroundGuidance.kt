package com.fileapex.platform

/**
 * OEM-specific copy for Android background / battery setup.
 * Detection lives in [BackgroundPersistenceGuidance] (androidMain).
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
    /** Primary path to allow background / unrestricted app battery usage. */
    val appBatteryUsageSteps: String,
    /** Optional second step (auto-start, sleeping apps, etc.). */
    val autoStartHint: String?
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
                appBatteryUsageSteps =
                    "Settings → Apps → FileApex → App battery usage → Always allow",
                autoStartHint =
                    "If radios do not respond after an update, also check Settings → Battery → " +
                        "Manage background apps and set FileApex to Always allow."
            )
            OemVendor.Samsung -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "Samsung",
                appBatteryUsageSteps =
                    "Settings → Apps → FileApex → Battery → Unrestricted",
                autoStartHint =
                    "Also add FileApex under Settings → Battery → Background usage limits → " +
                        "Never sleeping apps, and disable Sleeping apps if listed."
            )
            OemVendor.Pixel -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "Pixel",
                appBatteryUsageSteps =
                    "Settings → Apps → FileApex → App battery usage → Unrestricted",
                autoStartHint = null
            )
            OemVendor.Oppo -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "Oppo",
                appBatteryUsageSteps =
                    "Settings → Apps → FileApex → Battery → Allow background activity",
                autoStartHint =
                    "Enable auto-launch: Settings → Apps → App management → Auto launch → FileApex."
            )
            OemVendor.OnePlus -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "OnePlus",
                appBatteryUsageSteps =
                    "Settings → Apps → FileApex → Battery → Don't optimize",
                autoStartHint =
                    "Enable auto-launch: Settings → Apps → App management → Auto launch → FileApex."
            )
            OemVendor.Xiaomi -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "Xiaomi",
                appBatteryUsageSteps =
                    "Settings → Apps → FileApex → Battery saver → No restrictions",
                autoStartHint =
                    "Enable Autostart: Settings → Apps → Manage apps → FileApex → Autostart."
            )
            OemVendor.Poco -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "Poco",
                appBatteryUsageSteps =
                    "Settings → Apps → FileApex → Battery saver → No restrictions",
                autoStartHint =
                    "Enable Autostart: Settings → Apps → Manage apps → FileApex → Autostart."
            )
            OemVendor.Vivo -> OemBackgroundGuidance(
                vendor = vendor,
                vendorLabel = "Vivo",
                appBatteryUsageSteps =
                    "Settings → Apps → FileApex → Battery → Allow background activity",
                autoStartHint =
                    "Allow high background power: Settings → Battery → Background power " +
                        "consumption management → FileApex."
            )
            OemVendor.Other -> error("Other has no guidance")
        }
    }
}
