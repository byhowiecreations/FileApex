package com.fileapex.ui.explorer

import com.fileapex.ui.transfer.TransferRowDensity
import com.fileapex.ui.transfer.LiveTransferRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.Image
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.i18n.stringRes
import com.fileapex.platform.DownloadsPaths
import com.fileapex.di.FileApexServices
import com.fileapex.iroh.IrohRuntime
import com.fileapex.presentation.DeviceListRow
import com.fileapex.presentation.ExplorerActionCopy

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
        val online by FileApexServices.presenceMonitor.onlineDeviceIds.collectAsState()
        val reachedVia by FileApexServices.presenceMonitor.reachedVia.collectAsState()
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
                                        DeviceListRow.pickerStatus(
                                            online = option.deviceId in online,
                                            reachedBy = reachedVia[option.deviceId],
                                            irohPath = IrohRuntime.livePath(option.deviceId),
                                            lanHost = option.host
                                        )
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
                    LiveTransferRow(TransferRowDensity.Inline, Modifier.padding(bottom = 4.dp))
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

@Composable
internal fun ExplorerScope.ExplorerPreviewDialog() {
    with(inputs) { with(frame) { with(live) {
    val preview = state.previewItem
    if (preview != null || state.isPreviewLoading) {
        val files = state.contentFiles
        val neighbours = preview?.let { FileViewerLogic.neighbours(files, it) }
        FileViewer(
            name = preview?.name ?: stringRes("preview"),
            sizeText = preview?.let { com.fileapex.platform.formatHostFileSize(it.sizeBytes) }.orEmpty(),
            position = preview?.let { FileViewerLogic.position(files, it) },
            body = when {
                state.isPreviewLoading -> ViewerBody.Loading
                state.previewImage != null -> ViewerBody.Image(state.previewImage!!)
                state.previewPages.isNotEmpty() -> ViewerBody.Pages(state.previewPages)
                state.previewText != null -> ViewerBody.Text(state.previewText!!)
                else -> ViewerBody.Unsupported
            },
            canDownload = state.canDownloadPreview,
            downloading = state.isDownloading,
            onDownload = viewModel::downloadPreview,
            onClose = viewModel::dismissPreview,
            onPrevious = neighbours?.previous?.let { target -> { viewModel.activateFile(target) } },
            onNext = neighbours?.next?.let { target -> { viewModel.activateFile(target) } }
        )
    }
    } } }
}
