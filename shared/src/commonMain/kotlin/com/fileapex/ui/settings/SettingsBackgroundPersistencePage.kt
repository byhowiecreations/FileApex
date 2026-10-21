package com.fileapex.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.platform.BackgroundPersistenceUiState
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.SettingsScreenLayoutMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackgroundPersistenceSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onEnableServiceWatchdog: (Boolean) -> Unit,
    backgroundPersistence: BackgroundPersistenceUiState,
    onRequestBatteryUnrestricted: () -> Unit,
    onOpenBackgroundPersistenceSettings: () -> Unit,
    onOpenUnusedAppRestrictionsSettings: () -> Unit,
    onOpenAppBatteryUsageSettings: () -> Unit,
    exactAlarmWarningActive: Boolean,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit
) {
    SettingsPageShell(
        title = stringRes("background_persistence"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("service_watchdog"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("watchdog_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.enableServiceWatchdog,
                        onCheckedChange = onEnableServiceWatchdog
                    )
                }
            )
            if (backgroundPersistence.backgroundRestricted) {
                ListItem(
                    headlineContent = { Text(stringRes("background_restricted"), softWrap = true) },
                    supportingContent = {
                        Text(
                            backgroundPersistence.oemGuidance?.let { guidance ->
                                stringRes(
                                    "background_restricted_desc_steps",
                                    stringRes(guidance.batteryStepsKey)
                                )
                            } ?: stringRes("background_restricted_desc"),
                            softWrap = true
                        )
                    },
                    modifier = Modifier.clickable { onOpenAppBatteryUsageSettings() }
                )
            }
            if (backgroundPersistence.batteryOptimizationRestricted) {
                ListItem(
                    headlineContent = { Text(stringRes("battery_optimization_active"), softWrap = true) },
                    supportingContent = {
                        Text(stringRes("battery_optimization_desc"), softWrap = true)
                    },
                    modifier = Modifier.clickable { onRequestBatteryUnrestricted() }
                )
            }
            if (backgroundPersistence.unusedAppRestrictionsActive) {
                ListItem(
                    headlineContent = { Text(stringRes("pause_unused"), softWrap = true) },
                    supportingContent = {
                        Text(stringRes("pause_unused_desc"), softWrap = true)
                    },
                    modifier = Modifier.clickable { onOpenUnusedAppRestrictionsSettings() }
                )
            }
            backgroundPersistence.oemGuidance?.let { guidance ->
                if (backgroundPersistence.showOemSetup) {
                    ListItem(
                        headlineContent = { Text(stringRes("vendor_setup", guidance.vendorLabel), softWrap = true) },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(stringRes(guidance.batteryStepsKey))
                                    guidance.autoStartHintKey?.let { hintKey ->
                                        append('\n')
                                        append(stringRes(hintKey))
                                    }
                                },
                                softWrap = true
                            )
                        },
                        modifier = Modifier.clickable { onOpenBackgroundPersistenceSettings() }
                    )
                }
            }
            if (!com.fileapex.di.FileApexServices.isPlayStoreBuild && exactAlarmWarningActive) {
                ListItem(
                    headlineContent = { Text(stringRes("exact_alarms_disabled"), softWrap = true) },
                    supportingContent = {
                        Text(stringRes("exact_alarms_desc"), softWrap = true)
                    },
                    modifier = Modifier.clickable { onOpenExactAlarmSettings() }
                )
            }
            ListItem(
                headlineContent = { Text(stringRes("system_app_settings"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("system_app_settings_desc"), softWrap = true)
                },
                modifier = Modifier.clickable { onOpenAppDetailsSettings() }
            )
        }
    }
}
