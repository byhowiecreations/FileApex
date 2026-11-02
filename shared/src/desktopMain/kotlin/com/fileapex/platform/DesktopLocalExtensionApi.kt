package com.fileapex.platform

import com.fileapex.di.FileApexServices
import com.fileapex.friends.FriendShareTarget
import com.fileapex.friends.FriendsHub
import com.fileapex.i18n.AppI18n
import com.fileapex.network.LocalExtensionApi
import com.fileapex.network.LocalShareDevice
import com.fileapex.network.LocalShareResult
import com.fileapex.util.cancellableCatching
import java.io.File

internal data class ShareRow(val deviceId: String, val deviceName: String, val isRemoved: Boolean, val ready: Boolean)

internal data class SendOutcome(val sent: Boolean, val queued: Boolean, val message: String)

/**
 * Finder extensions ask the running app for destinations and dispatch. Friend ids are local share targets only;
 * they are never added to the paired-device repository or sent through its transfer queue.
 */
internal class DesktopLocalExtensionApi(
    private val devices: suspend () -> List<ShareRow>,
    private val sendOrQueue: suspend (paths: List<String>, deviceIds: List<String>) -> SendOutcome,
    private val postFile: suspend (path: String, caption: String) -> Unit,
    private val postText: suspend (String) -> Unit,
    private val text: (String) -> String,
    private val friends: suspend () -> List<FriendShareTarget>,
    private val sendFriendPaths: suspend (paths: List<String>, friendKey: String) -> Boolean
) : LocalExtensionApi {
    override suspend fun devices(): List<LocalShareDevice> =
        devices.invoke().filter { !it.isRemoved }.map { LocalShareDevice(it.deviceId, it.deviceName, it.ready) } +
            friends().map { LocalShareDevice("friend:${it.key}", "${it.label} (${it.group})", true) }

    private suspend fun sendDevices(paths: List<String>, deviceIds: List<String>): LocalShareResult =
        cancellableCatching { sendOrQueue(paths, deviceIds) }.fold(
            onSuccess = { LocalShareResult(it.sent || it.queued, it.message, if (it.sent || it.queued) deviceIds else emptyList()) },
            onFailure = { LocalShareResult(false, it.message ?: text("send_failed")) }
        )

    override suspend fun send(paths: List<String>, deviceIds: List<String>): LocalShareResult {
        val (friendIds, pairedIds) = deviceIds.distinct().partition(::isFriendTrayId)
        if (friendIds.isEmpty()) return sendDevices(paths, pairedIds)
        val known = friends().map { "friend:${it.key}" }.toSet()
        if (friendIds.any { it !in known }) return LocalShareResult(false, text("friends_send_dropped_no_friend"))
        val results = mutableListOf<LocalShareResult>()
        if (pairedIds.isNotEmpty()) results += sendDevices(paths, pairedIds)
        friendIds.forEach { id ->
            results += cancellableCatching { sendFriendPaths(paths, id.removePrefix("friend:")) }.fold(
                onSuccess = { accepted -> LocalShareResult(accepted, text(if (accepted) "friends_share_queued" else "friends_share_failed"), if (accepted) listOf(id) else emptyList()) },
                onFailure = { LocalShareResult(false, text("friends_share_failed")) }
            )
        }
        return LocalShareResult(results.all { it.ok }, results.joinToString(" · ") { it.message }, results.flatMap { it.acceptedIds })
    }

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
            text = { AppI18n.t(it) },
            friends = { FriendsHub.shareTargets(Int.MAX_VALUE) },
            sendFriendPaths = { paths, key -> key in FriendsHub.enqueueShared(listOf(key), paths.map(::File)) }
        )
    }
}
