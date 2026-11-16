package com.fileapex.ui

import androidx.compose.runtime.Composable
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.data.settings.DesktopLayoutMode
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.platform.BackgroundPersistenceUiState
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.settings.clipboardShareTargetsSubtitle

internal fun backgroundPersistenceSubtitle(
    watchdogEnabled: Boolean,
    backgroundPersistence: BackgroundPersistenceUiState,
    exactAlarmWarningActive: Boolean
): String {
    val status = if (watchdogEnabled) AppI18n.t("on") else AppI18n.t("off")
    val warnings = buildList {
        if (backgroundPersistence.backgroundRestricted) add(AppI18n.t("warn_background_restricted"))
        if (backgroundPersistence.batteryOptimizationRestricted) add(AppI18n.t("warn_battery_optimized"))
        if (backgroundPersistence.unusedAppRestrictionsActive) add(AppI18n.t("warn_hibernation"))
        if (!com.fileapex.di.FileApexServices.isPlayStoreBuild && exactAlarmWarningActive) add(AppI18n.t("warn_alarms_off"))
        if (backgroundPersistence.showOemSetup &&
            !backgroundPersistence.persistenceRestricted &&
            !backgroundPersistence.unusedAppRestrictionsActive
        ) {
            add(AppI18n.t("warn_oem_app_launch"))
        }
    }
    return if (warnings.isEmpty()) {
        status
    } else {
        AppI18n.t("status_on_warnings", status, warnings.joinToString(", "))
    }
}

internal fun clipboardSettingsSubtitle(state: SettingsUiState): String {
    if (!state.clipboardSharingEnabled) return AppI18n.t("off")
    val mode = clipboardShareTargetsSubtitle(state)
    val extras = buildList {
        if (currentPlatformLabel() == "Android" && state.clipboardAccessibilityEnabled) add(AppI18n.t("accessibility"))
        if (currentPlatformLabel() == "Android" && !com.fileapex.di.FileApexServices.isPlayStoreBuild && state.clipboardShizukuEnabled) add(AppI18n.t("diag_shizuku_active"))
        if (currentPlatformLabel() != "Android" && state.clipboardAutoSendEnabled) add(AppI18n.t("auto_send"))
        if (state.clipboardViaCellularEnabled && currentPlatformLabel() == "Android") add(AppI18n.t("cellular"))
    }
    return if (extras.isEmpty()) {
        AppI18n.t("clipboard_on_mode", mode)
    } else {
        AppI18n.t("clipboard_on_mode_extras", mode, extras.joinToString(" · "))
    }
}

@Composable
internal fun localizedDesktopLayout(mode: DesktopLayoutMode): String = when (mode) {
    DesktopLayoutMode.Compact -> stringRes("layout_compact")
    DesktopLayoutMode.Expanded -> stringRes("layout_expanded")
}

@Composable
internal fun localizedDesktopUiStyle(style: DesktopUiStyle): String = when (style) {
    DesktopUiStyle.Standard -> stringRes("windows_standard")
    DesktopUiStyle.WindowsFluent -> stringRes("windows_fluent")
}
