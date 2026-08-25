package com.fileapex.platform

enum class ClipboardCheckStatus {
    GRANTED,
    MISSING,
    NOT_REQUIRED
}

data class ClipboardCheckResult(
    val id: String,
    val required: Boolean,
    val status: ClipboardCheckStatus
)

object ClipboardDiagnosticsPolicy {
    const val ID_SHARING = "sharing"
    const val ID_RECIPIENTS = "recipients"
    const val ID_A11Y_SETTING = "a11y_setting"
    const val ID_A11Y_SYSTEM = "a11y_system"
    const val ID_A11Y_BOUND = "a11y_bound"
    const val ID_BATTERY = "battery"
    const val ID_NOTIFICATIONS = "notifications"
    const val ID_RESTRICTED = "restricted"
    const val ID_SHIZUKU = "shizuku"

    fun shouldShowEntry(sharingEnabled: Boolean): Boolean = sharingEnabled

    fun recipientsChosen(shareModeAll: Boolean, shareModeSpecific: Boolean, specificTargetCount: Int): Boolean {
        if (shareModeAll) return true
        if (shareModeSpecific) return specificTargetCount > 0
        return false
    }

    fun checks(
        sharingEnabled: Boolean,
        recipientsChosen: Boolean,
        accessibilitySettingEnabled: Boolean,
        accessibilityListed: Boolean,
        accessibilityBound: Boolean,
        batteryWhitelisted: Boolean,
        notificationsEnabled: Boolean,
        restrictedSettingsRelevant: Boolean,
        restrictedSettingsBlocked: Boolean,
        shizukuActive: Boolean,
        shizukuOptedIn: Boolean
    ): List<ClipboardCheckResult> {
        val rows = mutableListOf(
            required(ID_SHARING, sharingEnabled),
            required(ID_RECIPIENTS, recipientsChosen),
            required(ID_A11Y_SETTING, accessibilitySettingEnabled),
            required(ID_A11Y_SYSTEM, accessibilityListed),
            required(ID_A11Y_BOUND, accessibilityBound),
            required(ID_BATTERY, batteryWhitelisted),
            required(ID_NOTIFICATIONS, notificationsEnabled)
        )
        if (restrictedSettingsRelevant) {
            rows += required(ID_RESTRICTED, !restrictedSettingsBlocked)
        }
        rows += ClipboardCheckResult(
            id = ID_SHIZUKU,
            required = false,
            status = ClipboardShizukuPolicy.diagnosticsStatus(
                optedIn = shizukuOptedIn,
                active = shizukuActive
            )
        )
        return rows
    }

    fun allRequiredGranted(checks: List<ClipboardCheckResult>): Boolean {
        return checks.filter { it.required }.all { it.status == ClipboardCheckStatus.GRANTED }
    }

    private fun required(id: String, granted: Boolean): ClipboardCheckResult {
        return ClipboardCheckResult(
            id = id,
            required = true,
            status = if (granted) ClipboardCheckStatus.GRANTED else ClipboardCheckStatus.MISSING
        )
    }
}
