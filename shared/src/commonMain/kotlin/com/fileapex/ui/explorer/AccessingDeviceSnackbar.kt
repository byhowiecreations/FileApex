package com.fileapex.ui.explorer

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.fileapex.i18n.stringRes

internal fun shouldShowAccessingSnackbar(isRemote: Boolean, firstListingSettled: Boolean): Boolean = isRemote && !firstListingSettled

/** Shows "Accessing {device}" until a remote device's first listing has arrived (or failed, or asked for a PIN). Leaving the composition takes it down. */
@Composable
internal fun AccessingDeviceSnackbar(host: SnackbarHostState, isRemote: Boolean, firstListingSettled: Boolean, deviceName: String) {
    val message = stringRes("accessing_device", deviceName)
    LaunchedEffect(isRemote, firstListingSettled, deviceName) {
        if (shouldShowAccessingSnackbar(isRemote, firstListingSettled)) {
            host.showSnackbar(message, duration = SnackbarDuration.Indefinite)
        }
    }
}
