package com.omninode.network

import com.omninode.data.db.PairedDeviceEntity
import com.omninode.domain.model.RemoteFileItem
import com.omninode.domain.pairing.ClusterSyncRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readAtMostTo
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlinx.serialization.json.Json

/**
 * Single Ktor client for remote file list/stream/upload against paired nodes.
 * Uses the process-wide [HttpClient] from [com.omninode.di.OmniNodeServices].
 */
class OmniNodeClient(
    private val client: HttpClient,
    private val json: Json = OmniHttpClientFactory.defaultJson
) {
    /** In-memory PINs for peers that require PIN this session (host:port → pin). */
    private val sessionPinsLock = Any()
    private val sessionPins = mutableMapOf<String, String>()

    fun rememberSessionPin(host: String, port: Int, pin: String) {
        val trimmed = pin.trim()
        if (trimmed.isNotEmpty()) {
            synchronized(sessionPinsLock) {
                sessionPins[endpointKey(host, port)] = trimmed
            }
        }
    }

    fun clearSessionPin(host: String, port: Int) {
        synchronized(sessionPinsLock) {
            sessionPins.remove(endpointKey(host, port))
        }
    }

    private fun endpointKey(host: String, port: Int): String = "$host:$port"

    private fun io.ktor.client.request.HttpRequestBuilder.attachSessionPin(host: String, port: Int) {
        val pin = synchronized(sessionPinsLock) {
            sessionPins[endpointKey(host, port)]
        }
        pin?.let { parameter("pin", it) }
    }

    suspend fun listFiles(host: String, port: Int, path: String): List<RemoteFileItem> {
        val response = client.get("http://$host:$port/api/v1/files/list") {
            parameter("path", path)
            attachSessionPin(host, port)
        }
        if (response.status.value == 403) {
            error("PIN required — open the device and enter its PIN")
        }
        if (!response.status.isSuccess()) {
            error("List failed (${response.status}): $host:$port$path")
        }
        return response.body()
    }

    suspend fun fetchIdentity(host: String, port: Int): NodeIdentityResponse {
        val response = client.get("http://$host:$port/api/v1/identity")
        if (!response.status.isSuccess()) {
            error("Identity check failed (${response.status})")
        }
        return response.body()
    }

    /**
     * Verifies [pin] against a peer that has PIN required.
     * On success, remembers the PIN for subsequent file API calls this session.
     */
    suspend fun verifyPin(host: String, port: Int, pin: String) {
        val trimmed = pin.trim()
        require(trimmed.isNotEmpty()) { "PIN is required" }
        val response = client.post("http://$host:$port/api/v1/auth/verify-pin") {
            parameter("pin", trimmed)
        }
        if (response.status.value == 403) {
            error("Incorrect PIN")
        }
        if (!response.status.isSuccess()) {
            error("PIN check failed (${response.status})")
        }
        rememberSessionPin(host, port, trimmed)
    }

    /** Lightweight liveness probe used by PeerPresenceMonitor. */
    suspend fun pingHealth(host: String, port: Int): Boolean {
        return runCatching {
            kotlinx.coroutines.withTimeout(2_500) {
                val response = client.get("http://$host:$port/api/v1/health")
                response.status.isSuccess()
            }
        }.getOrDefault(false)
    }

    /**
     * Completes the reverse half of a dual-pairing handshake by registering
     * this scanner on the broadcaster that showed the QR code.
     */
    suspend fun postPairingRespond(
        host: String,
        port: Int,
        scannerDevice: PairedDeviceEntity,
        pin: String? = null
    ) {
        val response = client.post("http://$host:$port/api/v1/pairing/respond") {
            contentType(ContentType.Application.Json)
            if (!pin.isNullOrBlank()) {
                parameter("pin", pin)
            }
            setBody(scannerDevice)
        }
        if (response.status.value == 403) {
            error("Incorrect PIN — pairing rejected")
        }
        if (!response.status.isSuccess()) {
            error("Pairing handshake failed (${response.status})")
        }
    }

    /**
     * Asks a remote node to adopt [newName] as its local display name and broadcast it.
     */
    suspend fun postRemoteRename(
        host: String,
        port: Int,
        newName: String
    ) {
        val response = client.post("http://$host:$port/api/v1/identity/rename") {
            contentType(ContentType.Application.Json)
            setBody(RenameDeviceRequest(deviceName = newName.trim()))
        }
        if (!response.status.isSuccess()) {
            error("Remote rename failed (${response.status})")
        }
    }

    /**
     * One-hop cluster sync: push introducer + device list to a peer for Room upsert.
     */
    suspend fun postClusterSync(
        host: String,
        port: Int,
        request: ClusterSyncRequest
    ) {
        val response = client.post("http://$host:$port/api/v1/devices/merge") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            error("Cluster sync failed (${response.status})")
        }
    }

    suspend fun listPairedDevices(host: String, port: Int): List<PairedDeviceEntity> {
        val response = client.get("http://$host:$port/api/v1/devices")
        if (!response.status.isSuccess()) {
            error("Device list failed (${response.status})")
        }
        return response.body()
    }

    suspend fun downloadBytes(
        host: String,
        port: Int,
        remotePath: String,
        maxBytes: Long = 25L * 1024L * 1024L
    ): ByteArray {
        return client.prepareGet("http://$host:$port/api/v1/files/stream") {
            parameter("path", remotePath)
            attachSessionPin(host, port)
        }.execute { response ->
            if (response.status.value == 403) {
                error("PIN required — open the device and enter its PIN")
            }
            if (!response.status.isSuccess()) {
                error("Download failed (${response.status})")
            }
            val channel = response.bodyAsChannel()
            // Single sink buffer — avoid chunk list + second full-size ByteArray peak.
            val sink = Buffer()
            val buffer = ByteArray(8192)
            var total = 0L
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read > 0) {
                    total += read
                    if (total > maxBytes) {
                        error("File is too large to preview (>${maxBytes / (1024 * 1024)} MB)")
                    }
                    sink.write(buffer, startIndex = 0, endIndex = read)
                }
            }
            sink.readByteArray()
        }
    }

    suspend fun downloadToLocal(
        host: String,
        port: Int,
        remotePath: String,
        localTargetPath: String
    ) {
        client.prepareGet("http://$host:$port/api/v1/files/stream") {
            parameter("path", remotePath)
            attachSessionPin(host, port)
        }.execute { response ->
            if (response.status.value == 403) {
                error("PIN required — open the device and enter its PIN")
            }
            if (!response.status.isSuccess()) {
                error("Download failed (${response.status})")
            }
            val target = Path(localTargetPath)
            target.parent?.let { parent ->
                if (!SystemFileSystem.exists(parent)) {
                    SystemFileSystem.createDirectories(parent)
                }
            }
            val channel = response.bodyAsChannel()
            SystemFileSystem.sink(target).buffered().use { sink ->
                val buffer = ByteArray(8192)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read > 0) {
                        sink.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    /**
     * Streams a remote file and invokes [onChunk] for each buffer without holding the file in memory.
     */
    suspend fun streamRemoteFile(
        host: String,
        port: Int,
        remotePath: String,
        onChunk: suspend (ByteArray) -> Unit
    ) {
        client.prepareGet("http://$host:$port/api/v1/files/stream") {
            parameter("path", remotePath)
            attachSessionPin(host, port)
        }.execute { response ->
            if (response.status.value == 403) {
                error("PIN required — open the device and enter its PIN")
            }
            if (!response.status.isSuccess()) {
                error("Stream failed (${response.status})")
            }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(CHUNK_SIZE)
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read > 0) {
                    onChunk(buffer.copyOf(read))
                }
            }
        }
    }

    /**
     * Streams a local file to a peer via POST /api/v1/files/upload without buffering the whole file.
     */
    suspend fun uploadFromLocal(
        host: String,
        port: Int,
        localSourcePath: String,
        remoteTargetPath: String
    ) {
        val source = Path(localSourcePath)
        check(SystemFileSystem.exists(source)) { "Local source missing: $localSourcePath" }
        val size = SystemFileSystem.metadataOrNull(source)?.size
        val response = client.post("http://$host:$port/api/v1/files/upload") {
            parameter("targetPath", remoteTargetPath)
            attachSessionPin(host, port)
            contentType(ContentType.Application.OctetStream)
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override val contentType: ContentType = ContentType.Application.OctetStream
                    override val contentLength: Long? = size

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        SystemFileSystem.source(source).buffered().use { input ->
                            val buffer = ByteArray(CHUNK_SIZE)
                            while (!input.exhausted()) {
                                val read = input.readAtMostTo(buffer)
                                if (read > 0) {
                                    channel.writeFully(buffer, 0, read)
                                }
                            }
                        }
                    }
                }
            )
        }
        if (response.status.value == 403) {
            error("PIN required — open the device and enter its PIN")
        }
        if (!response.status.isSuccess()) {
            error("Upload failed (${response.status})")
        }
    }

    /**
     * Uploads chunk packets from [chunks] to a peer (used by Multi Copy fan-out).
     */
    suspend fun uploadFromChunkChannel(
        host: String,
        port: Int,
        remoteTargetPath: String,
        chunks: ReceiveChannel<ByteArray>,
        contentLength: Long? = null
    ) {
        val response = client.post("http://$host:$port/api/v1/files/upload") {
            parameter("targetPath", remoteTargetPath)
            attachSessionPin(host, port)
            contentType(ContentType.Application.OctetStream)
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override val contentType: ContentType = ContentType.Application.OctetStream
                    override val contentLength: Long? = contentLength

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        for (chunk in chunks) {
                            channel.writeFully(chunk)
                        }
                    }
                }
            )
        }
        if (response.status.value == 403) {
            error("PIN required — open the device and enter its PIN")
        }
        if (!response.status.isSuccess()) {
            error("Upload failed (${response.status})")
        }
    }

    fun close() {
        // Shared process HttpClient lifecycle is owned by OmniNodeServices — do not close here.
    }

    companion object {
        const val CHUNK_SIZE = 64 * 1024
    }
}

@kotlinx.serialization.Serializable
data class NodeIdentityResponse(
    val deviceId: String,
    val deviceName: String,
    val rootPath: String,
    val port: Int,
    /** Absolute Downloads/OmniNode path on this node (Multi Copy / Download landing zone). */
    val downloadsPath: String = "",
    /** When true, pairing requires this device's PIN. */
    val pinRequired: Boolean = false,
    /** Marketing app version from [com.omninode.update.currentAppVersionName]. */
    val appVersion: String = ""
)

@kotlinx.serialization.Serializable
data class RenameDeviceRequest(
    val deviceName: String
)
