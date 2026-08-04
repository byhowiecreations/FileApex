package com.fileapex.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fileapex.presentation.TransferQueueViewModel

/** Device picker for header drop-target adds — host at app root. */
@Composable
fun TransferQueueDropHost(viewModel: TransferQueueViewModel) {
    val state by viewModel.uiState.collectAsState()
    if (state.showDevicePicker) {
        QueueAddDevicePickerDialog(
            deviceOptions = state.deviceOptions,
            selectedDeviceIds = state.selectedDeviceIds,
            isLoading = state.isLoadingDevices,
            onToggleDevice = viewModel::toggleDevice,
            onDismiss = viewModel::dismissDevicePicker,
            onConfirm = viewModel::confirmEnqueueDropped
        )
    }
}
