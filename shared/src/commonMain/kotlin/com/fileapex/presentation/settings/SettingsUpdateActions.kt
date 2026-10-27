package com.fileapex.presentation.settings

import com.fileapex.data.settings.UpdateCheckFrequency
import com.fileapex.data.settings.UpdateCheckUnit
import com.fileapex.di.FileApexServices
import com.fileapex.update.AppUpdateCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import com.fileapex.presentation.SettingsUiState

internal class SettingsUpdateActions(
    private val _uiState: MutableStateFlow<SettingsUiState>
) {
    private val settings = FileApexServices.settings

    fun setCheckForUpdates(enabled: Boolean) {
        settings.setCheckForUpdatesEnabled(enabled)
        _uiState.update { it.copy(checkForUpdatesEnabled = enabled) }
        if (enabled) {
            AppUpdateCoordinator.onCheckForUpdatesEnabled()
        } else {
            AppUpdateCoordinator.onCheckForUpdatesDisabled()
        }
    }

    fun setCheckForUpdatesUnit(unit: UpdateCheckUnit) {
        val amount = UpdateCheckFrequency.sanitizeAmount(
            unit,
            _uiState.value.checkForUpdatesIntervalAmount
        )
        settings.setCheckForUpdatesInterval(unit, amount)
        _uiState.update {
            it.copy(
                checkForUpdatesIntervalUnit = unit,
                checkForUpdatesIntervalAmount = amount,
                checkForUpdatesAmountText = amount.toString()
            )
        }
        AppUpdateCoordinator.onScheduleChanged()
    }

    fun setCheckForUpdatesAmountText(raw: String) {
        val digits = raw.filter { it.isDigit() }.take(2)
        _uiState.update { it.copy(checkForUpdatesAmountText = digits) }
        val parsed = digits.toIntOrNull() ?: return
        val unit = _uiState.value.checkForUpdatesIntervalUnit
        val amount = UpdateCheckFrequency.sanitizeAmount(unit, parsed)
        settings.setCheckForUpdatesInterval(unit, amount)
        _uiState.update {
            it.copy(
                checkForUpdatesIntervalAmount = amount,
                checkForUpdatesAmountText = if (digits.isEmpty()) "" else amount.toString()
            )
        }
        AppUpdateCoordinator.onScheduleChanged()
    }

    fun setCheckForUpdatesWeekAmount(amount: Int) {
        val safe = UpdateCheckFrequency.sanitizeAmount(UpdateCheckUnit.Weeks, amount)
        settings.setCheckForUpdatesInterval(UpdateCheckUnit.Weeks, safe)
        _uiState.update {
            it.copy(
                checkForUpdatesIntervalUnit = UpdateCheckUnit.Weeks,
                checkForUpdatesIntervalAmount = safe,
                checkForUpdatesAmountText = safe.toString()
            )
        }
        AppUpdateCoordinator.onScheduleChanged()
    }
}
