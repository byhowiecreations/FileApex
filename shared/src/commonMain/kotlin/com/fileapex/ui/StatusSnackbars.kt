package com.fileapex.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.fileapex.presentation.DevicesUiState

/** Shows the Devices view model's status, error and details-hint messages on the screen's snackbar. */
@Composable
fun DevicesSnackbars(
    state: DevicesUiState,
    host: SnackbarHostState,
    progressShownElsewhere: Boolean,
    onShown: () -> Unit
) {
    LaunchedEffect(state.statusMessage, state.errorMessage) {
        state.statusMessage?.let {
            if (shouldShowStatusSnackbar(progressShownElsewhere)) host.showSnackbar(it)
            onShown()
        }
        state.errorMessage?.let {
            host.showSnackbar(it)
            onShown()
        }
    }
    // Cancelling this effect when the hint clears is what takes the snackbar down.
    LaunchedEffect(state.detailsHint) {
        state.detailsHint?.let { host.showSnackbar(it, duration = SnackbarDuration.Long) }
    }
}
