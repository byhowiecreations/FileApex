package com.fileapex.ui.explorer

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.i18n.stringRes
import com.fileapex.platform.DownloadsPaths
import com.fileapex.presentation.ExplorerActionCopy
import com.fileapex.ui.TransferProgressBlock
import com.fileapex.util.NetworkUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExplorerScope.ExplorerPinAndIntroDialogs() {
    with(inputs) { with(frame) { with(live) {
    if (state.pendingPinUnlock) {
        AlertDialog(
            onDismissRequest = {
                pinText = ""
                viewModel.cancelPinUnlock()
            },
            title = { Text(stringRes("enter_device_pin")) },
            text = {
                Column {
                    Text(
                        text = stringRes("pin_session_expired", state.deviceTitle),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { pinText = it.filter { ch -> ch.isDigit() }.take(8) },
                        singleLine = true,
                        label = { Text(stringRes("pin")) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = state.pinUnlockError != null,
                        supportingText = state.pinUnlockError?.let { err ->
                            {
                                Text(err, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmPinUnlock(pinText)
                        pinText = ""
                    },
                    enabled = pinText.isNotBlank()
                ) {
                    Text(stringRes("unlock"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pinText = ""
                        viewModel.cancelPinUnlock()
                    }
                ) { Text(stringRes("cancel")) }
            }
        )
    }

    if (state.showMultiCopyIntro) {
        AlertDialog(
            onDismissRequest = viewModel::dismissMultiCopyIntro,
            title = { Text(ExplorerActionCopy.SEND_TO_INTRO_TITLE) },
            text = {
                Text(ExplorerActionCopy.SEND_TO_INTRO_BODY)
            },
            confirmButton = {
                TextButton(onClick = viewModel::acknowledgeMultiCopyIntro) {
                    Text(stringRes("ok"))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissMultiCopyIntro) {
                    Text(stringRes("cancel"))
                }
            }
        )
    }

    } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExplorerScope.ExplorerMultiCopyPicker() {
    with(inputs) { with(frame) { with(live) {
    if (state.showMultiCopyPicker) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissMultiCopyPicker,
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    text = ExplorerActionCopy.SEND_TO_PICKER_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringRes("files_land_in", DownloadsPaths.displayLabel()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    state.multiCopyOptions.forEach { option ->
                        val checked = option.deviceId in state.selectedMultiCopyDeviceIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = { viewModel.toggleMultiCopyDevice(option.deviceId) }
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(option.deviceName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = if (option.isLocal) {
                                        stringRes("local_device")
                                    } else {
                                        NetworkUtils.formatEndpointDisplay(option.host, option.port)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (state.isMultiCopying) {
                    TransferProgressBlock(modifier = Modifier.padding(bottom = 4.dp))
                    Text(
                        text = stringRes("send_continues_in_background"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                Row(modifier = Modifier.align(Alignment.End)) {
                    if (state.isMultiCopying) {
                        TextButton(onClick = viewModel::hideMultiCopyPicker) {
                            Text(stringRes("close"))
                        }
                    }
                    TextButton(
                        onClick = viewModel::confirmMultiCopy,
                        enabled = state.selectedMultiCopyDeviceIds.isNotEmpty() && !state.isMultiCopying
                    ) {
                        Text(ExplorerActionCopy.SEND_TO_PICKER_CONFIRM)
                    }
                }
            }
        }
    }

    } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExplorerScope.ExplorerPreviewDialog() {
    with(inputs) { with(frame) { with(live) {
    val preview = state.previewItem
    if (preview != null || state.isPreviewLoading) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPreview,
            title = { Text(preview?.name ?: stringRes("preview")) },
            text = {
                when {
                    state.isPreviewLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    state.previewImage != null -> {
                        Image(
                            bitmap = state.previewImage!!,
                            contentDescription = preview?.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 480.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    state.previewText != null -> {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(state.previewText!!)
                        }
                    }
                    else -> {
                        Text(stringRes("no_preview"))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPreview) { Text(stringRes("close")) }
            },
            dismissButton = {
                if (state.canDownloadPreview) {
                    TextButton(
                        onClick = viewModel::downloadPreview,
                        enabled = !state.isDownloading && !state.isPreviewLoading
                    ) {
                        Text(if (state.isDownloading) stringRes("downloading") else stringRes("download"))
                    }
                }
            }
        )
    }
    } } }
}
