package com.fileapex.domain.devices

import androidx.compose.ui.graphics.ImageBitmap
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.clipboard.ClipboardE2ee
import com.fileapex.domain.presence.PeerLanReachabilityVerdict
import com.fileapex.platform.encodePng
import com.fileapex.util.cancellableCatching
import java.util.Base64

/** Sends a device's custom icon to this device's paired peers, once, when the user chose to share it. */
object DeviceIconSyncCoordinator {
    data class Result(val sent: Int, val failed: Int, val unsupported: Int)

    /** [bitmap] null clears the icon on the peers. Peers that are offline are skipped; nothing is queued. */
    suspend fun push(targetDeviceId: String, bitmap: ImageBitmap?): Result = push(
        targetDeviceId = targetDeviceId,
        bitmap = bitmap,
        identityProvider = ::loadLocalIdentity,
        publicKeyProvider = { ClipboardE2ee.publicKeyBase64() },
        peersProvider = { FileApexServices.deviceRepository.listDevices() },
        endpointResolver = { peer -> FileApexServices.presenceMonitor.endpointIfOnline(peer) },
        postIcon = { host, port, req -> FileApexServices.client.deviceIcons.postDeviceIcon(host, port, req) }
    )

    internal suspend fun push(
        targetDeviceId: String,
        bitmap: ImageBitmap?,
        identityProvider: () -> LocalIdentity,
        publicKeyProvider: () -> String,
        peersProvider: suspend () -> List<PairedDeviceEntity>,
        endpointResolver: suspend (PairedDeviceEntity) -> PeerLanReachabilityVerdict.Direct?,
        postIcon: suspend (host: String, port: Int, DeviceIconSyncRequest) -> Boolean,
        pngEncoder: (ImageBitmap) -> ByteArray? = ::encodePng
    ): Result {
        val png = if (bitmap == null) "" else Base64.getEncoder().encodeToString(pngEncoder(bitmap) ?: return Result(0, 0, 0))
        val identity = identityProvider()
        val request = DeviceIconSyncRequest(
            senderDeviceId = identity.deviceId,
            senderPublicKey = publicKeyProvider(),
            targetDeviceId = targetDeviceId,
            png = png
        )
        var sent = 0
        var failed = 0
        var unsupported = 0
        val peers = peersProvider().filter { !it.isRemoved && it.deviceId != targetDeviceId }
        for (peer in peers) {
            val endpoint = endpointResolver(peer) ?: continue
            val outcome = cancellableCatching("DeviceIconSyncCoordinator push") {
                postIcon(endpoint.host, endpoint.port, request)
            }
            outcome.fold(
                onSuccess = { if (it) sent++ else unsupported++ },
                onFailure = { failed++ }
            )
        }
        return Result(sent, failed, unsupported)
    }
}
