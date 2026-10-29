package com.fileapex.platform

import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.network.LocalExtensionApi
import com.fileapex.network.LocalShareDevice
import com.fileapex.network.LocalShareResult
import com.fileapex.util.cancellableCatching
import java.io.File

internal data class ShareRow(val deviceId: String, val deviceName: String, val isRemoved: Boolean, val ready: Boolean)

internal data class SendOutcome(val sent: Boolean, val queued: Boolean, val message: String)

/**
 * What the Finder extensions ask of the app: the device list, a send and a Bulletin post. The files are read where they
 * are; nothing is copied or written for them. A send that went out or was queued is a success, as before.
 */
internal class DesktopLocalExtensionApi(
    private val devices: suspend () -> List<ShareRow>,
    private val sendOrQueue: suspend (paths: List<String>, deviceIds: List<String>) -> SendOutcome,
    private val postFile: suspend (path: String, caption: String) -> Unit,
    private val postText: suspend (String) -> Unit,
    private val text: (String) -> String
) : LocalExtensionApi {
    override suspend fun devices(): List<LocalShareDevice> =
        devices.invoke().filter { !it.isRemoved }.map { LocalShareDevice(it.deviceId, it.deviceName, it.ready) }

    override suspend fun send(paths: List<String>, deviceIds: List<String>): LocalShareResult =
        cancellableCatching { sendOrQueue(paths, deviceIds) }.fold(
            onSuccess = { LocalShareResult(it.sent || it.queued, it.message) },
            onFailure = { LocalShareResult(false, it.message ?: text("send_failed")) }
        )

    override suspend fun postBulletin(text: String, paths: List<String>): LocalShareResult =
        cancellableCatching {
            val path = paths.firstOrNull { it.isNotBlank() }
            if (path != null) postFile(path, text) else postText(text)
        }.fold(
            onSuccess = { LocalShareResult(true, text("posted_to_bulletin")) },
            onFailure = { LocalShareResult(false, it.message ?: text("could_not_post_bulletin")) }
        )

    companion object {
        fun live(): DesktopLocalExtensionApi = DesktopLocalExtensionApi(
            devices = {
                FileApexServices.deviceRepository.listDevices().map {
                    ShareRow(it.deviceId, it.deviceName, it.isRemoved, ready = FileApexServices.presenceMonitor.isDeviceOnline(it))
                }
            },
            sendOrQueue = { paths, ids ->
                FileApexServices.transferManager.awaitReady()
                val outcome = FileApexServices.transferQueue.sendLocalPathsOrQueue(absolutePaths = paths, deviceIds = ids)
                SendOutcome(sent = outcome.batch?.allFailed == false, queued = outcome.hadQueue, message = outcome.message)
            },
            postFile = { path, caption ->
                FileApexServices.bulletinSyncEngine.ingestSharedFile(path, File(path).name, File(path).length(), caption)
            },
            postText = { FileApexServices.bulletinSyncEngine.ingestSharedText(it) },
            text = { AppI18n.t(it) }
        )
    }
}
