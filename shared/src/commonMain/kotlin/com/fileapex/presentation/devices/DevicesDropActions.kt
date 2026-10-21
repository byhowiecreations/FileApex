package com.fileapex.presentation.devices

import com.fileapex.presentation.TransferDropUri
import androidx.lifecycle.viewModelScope
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.presence.RouteUse
import com.fileapex.domain.transfer.MultiCopyDeviceOption
import com.fileapex.domain.transfer.MultiCopySource
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.presentation.DevicesUiState
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.util.NetworkUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class DevicesDropActions(
    private val _uiState: MutableStateFlow<DevicesUiState>,
    private val viewModelScope: CoroutineScope
) {
    private val repository = FileApexServices.deviceRepository
    private val presence = FileApexServices.presenceMonitor
    private val transferManager = FileApexServices.transferManager
    private val identity: LocalIdentity
        get() = FileApexServices.localIdentity

    /**
     * Finder / Explorer drop onto a device tile. Folders keep nested structure on the peer.
     * Local "This device" drops are refused (no same-device remote transfer).
     */
    fun sendDroppedLocalFiles(deviceId: String, absolutePaths: List<String>) {
        viewModelScope.launch {
            if (deviceId == LocalIdentity.LOCAL_DEVICE_ID || deviceId == identity.deviceId) {
                // Entries dragged out of a remote device may land on this device; plain local paths may not.
                if (absolutePaths.any { TransferDropUri.isTransferUri(it) }) {
                    handleDroppedRemoteTransfer(LocalIdentity.LOCAL_DEVICE_ID, absolutePaths)
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        statusMessage = null,
                        errorMessage = AppI18n.t("cannot_send_to_self")
                    )
                }
                return@launch
            }

            if (absolutePaths.any { TransferDropUri.isTransferUri(it) }) {
                handleDroppedRemoteTransfer(deviceId, absolutePaths)
                return@launch
            }

            val roots = withContext(Dispatchers.IO) {
                absolutePaths.filter { path ->
                    runCatching {
                        val file = kotlinx.io.files.Path(path)
                        kotlinx.io.files.SystemFileSystem.exists(file)
                    }.getOrDefault(false)
                }
            }
            if (roots.isEmpty()) {
                _uiState.update {
                    it.copy(
                        statusMessage = null,
                        errorMessage = AppI18n.t("drop_files_or_folders")
                    )
                }
                return@launch
            }
            val target = repository.getDevice(deviceId)
            if (target == null) {
                _uiState.update {
                    it.copy(errorMessage = AppI18n.t("device_no_longer_paired"))
                }
                return@launch
            }
            _uiState.update {
                it.copy(errorMessage = null)
            }
            cancellableCatching {
                withContext(Dispatchers.IO) {
                    FileApexServices.transferQueue.sendLocalPathsOrQueue(roots, listOf(deviceId))
                }
            }.fold(
                onSuccess = { outcome ->
                    _uiState.update {
                        val batch = outcome.batch
                        val failed = batch?.allFailed != false && !outcome.hadQueue
                        if (failed) {
                            it.copy(
                                statusMessage = null,
                                errorMessage = outcome.message
                            )
                        } else {
                            it.copy(
                                statusMessage = outcome.message,
                                errorMessage = null
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            statusMessage = null,
                            errorMessage = UserFacingErrors.message(error, "send_failed")
                        )
                    }
                }
            )
        }
    }

    private suspend fun handleDroppedRemoteTransfer(targetDeviceId: String, uriList: List<String>) {
        val uris = uriList.mapNotNull { TransferDropUri.parse(it) }
        if (uris.isEmpty()) return
        for (uri in uris) {
            val sourceDeviceId = uri.sourceDeviceId
            val remotePath = if (uri.path.startsWith('/')) uri.path else "/${uri.path}"
            val fileName = uri.name
            val fileSize = uri.size

            if (sourceDeviceId == targetDeviceId) {
                _uiState.update { it.copy(errorMessage = AppI18n.t("cannot_send_to_self")) }
                return
            }

            if (sourceDeviceId == "local") {
                sendDroppedLocalFiles(targetDeviceId, listOf(remotePath))
                return
            }

            val sourceDevice = repository.getDevice(sourceDeviceId)
            if (sourceDevice == null) {
                _uiState.update { it.copy(errorMessage = AppI18n.t("device_no_longer_paired")) }
                return
            }

            val isTargetLocal = targetDeviceId == LocalIdentity.LOCAL_DEVICE_ID
            val targetDevice = if (isTargetLocal) null else repository.getDevice(targetDeviceId)
            if (!isTargetLocal && targetDevice == null) {
                _uiState.update { it.copy(errorMessage = AppI18n.t("device_no_longer_paired")) }
                return
            }

            val targetName = if (isTargetLocal) AppI18n.t("this_device") else targetDevice!!.deviceName

            _uiState.update {
                it.copy(
                    statusMessage = "Transferring $fileName from ${sourceDevice.deviceName} to $targetName...",
                    errorMessage = null
                )
            }

            val destination = if (isTargetLocal) {
                MultiCopyDeviceOption(
                    deviceId = LocalIdentity.LOCAL_DEVICE_ID,
                    deviceName = targetName,
                    isLocal = true,
                    host = com.fileapex.util.NetworkUtils.preferredLanIpv4(),
                    port = identity.sharePort,
                    destinationRoot = com.fileapex.platform.defaultDownloadsDir()
                )
            } else {
                val dev = targetDevice!!
                MultiCopyDeviceOption(
                    deviceId = dev.deviceId,
                    deviceName = dev.deviceName,
                    isLocal = false,
                    host = dev.lastKnownIp,
                    port = dev.port,
                    destinationRoot = com.fileapex.network.InboxTarget.ROOT
                )
            }

            val sourceEndpoint = presence.probeNow(sourceDevice, RouteUse.Bulk)
            val source = MultiCopySource.Remote(
                fileName = fileName,
                sizeBytes = fileSize,
                absolutePath = remotePath,
                host = sourceEndpoint?.host ?: sourceDevice.lastKnownIp,
                port = sourceEndpoint?.port ?: sourceDevice.port,
                isDirectory = false,
                relativeDestPath = fileName
            )

            withContext(Dispatchers.IO) {
                cancellableCatching {
                    FileApexServices.transferManager.sendToDevices(listOf(source), listOf(destination))
                }.fold(
                    onSuccess = { res ->
                        _uiState.update {
                            if (res.allFailed) {
                                val reason = res.results.flatMap { it.failures.values }.firstOrNull()?.let { ": $it" }.orEmpty()
                                it.copy(errorMessage = "Transfer of $fileName failed$reason", statusMessage = null)
                            } else {
                                it.copy(statusMessage = "Transferred $fileName to $targetName", errorMessage = null)
                            }
                        }
                    },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(errorMessage = "Transfer failed: ${err.message}", statusMessage = null)
                        }
                    }
                )
            }
        }
    }
}
