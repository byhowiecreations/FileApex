package com.omninode.platform

import com.omninode.di.OmniNodeServices
import com.omninode.domain.transfer.MultiCopyDeviceOption
import com.omninode.domain.transfer.MultiCopySource
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Finder Sync / Share Extension hand off selected files + devices to the main Mac app.
 * The main app runs [com.omninode.data.transfer.FileTransferService.multiCopyToDevices] —
 * the same path as in-app Multi Copy.
 *
 * Job files live under `~/Library/Application Support/com.omninode/send-jobs/`.
 */
object DesktopSendHandoff {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _incomingJobIds = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val incomingJobIds: SharedFlow<String> = _incomingJobIds.asSharedFlow()

    private val inFlight = mutableSetOf<String>()

    private val supportDir: File
        get() = File(
            System.getProperty("user.home"),
            "Library/Application Support/com.omninode"
        )

    private val jobsDir: File
        get() = File(supportDir, "send-jobs").also { it.mkdirs() }

    fun installOpenUriHandler() {
        runCatching {
            if (!Desktop.isDesktopSupported()) return
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.APP_OPEN_URI)) return
            desktop.setOpenURIHandler { event ->
                parseJobId(event.uri)?.let { jobId ->
                    println("DesktopSendHandoff: open URI job=$jobId")
                    _incomingJobIds.tryEmit(jobId)
                }
            }
            println("DesktopSendHandoff: APP_OPEN_URI handler installed")
        }.onFailure { error ->
            println("DesktopSendHandoff: URI handler not available :: ${error.message}")
        }
    }

    /** Pending jobs written while the app was quit (or URI delivery was missed). */
    fun listPendingJobIds(): List<String> {
        return jobsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.mapNotNull { file ->
                runCatching {
                    val job = json.decodeFromString<SendJobFile>(file.readText())
                    job.id.takeIf { job.status == STATUS_PENDING }
                }.getOrNull()
            }
            .orEmpty()
    }

    suspend fun processJob(jobId: String) = withContext(Dispatchers.IO) {
        synchronized(inFlight) {
            if (!inFlight.add(jobId)) return@withContext
        }
        try {
            processJobUnlocked(jobId)
        } finally {
            synchronized(inFlight) { inFlight.remove(jobId) }
        }
    }

    private suspend fun processJobUnlocked(jobId: String) {
        val file = jobFile(jobId)
        if (!file.isFile) {
            println("DesktopSendHandoff: missing job $jobId")
            return
        }
        val job = runCatching {
            json.decodeFromString<SendJobFile>(file.readText())
        }.getOrElse { error ->
            println("DesktopSendHandoff: bad job JSON $jobId :: ${error.message}")
            return
        }
        if (job.status == STATUS_DONE || job.status == STATUS_FAILED) return
        // STATUS_RUNNING with no in-flight owner = crashed mid-send; retry below.
        if (job.filePaths.isEmpty() || job.deviceIds.isEmpty()) {
            writeJob(job.copy(status = STATUS_FAILED, message = "Nothing to send"))
            return
        }

        writeJob(job.copy(status = STATUS_RUNNING, message = "Sending…"))
        runCatching {
            val peers = OmniNodeServices.deviceRepository.listDevices()
                .filter { it.deviceId in job.deviceIds.toSet() }
            check(peers.isNotEmpty()) { "Selected devices are not in the paired roster" }

            val options = peers.map { peer ->
                val downloadsRoot = runCatching {
                    val remote = OmniNodeServices.client.fetchIdentity(peer.lastKnownIp, peer.port)
                    remote.downloadsPath.trim().ifBlank {
                        TransferPaths.fallbackDownloadsPath(peer.rootPath)
                    }
                }.getOrElse {
                    TransferPaths.fallbackDownloadsPath(peer.rootPath)
                }
                MultiCopyDeviceOption(
                    deviceId = peer.deviceId,
                    deviceName = peer.deviceName,
                    isLocal = false,
                    host = peer.lastKnownIp,
                    port = peer.port,
                    destinationRoot = downloadsRoot
                )
            }

            val sources = job.filePaths.map { path ->
                val local = File(path)
                check(local.isFile) { "Missing file: $path" }
                MultiCopySource.Local(
                    fileName = local.name,
                    sizeBytes = local.length(),
                    absolutePath = local.absolutePath
                )
            }

            val results = OmniNodeServices.transferService.multiCopyToDevices(sources, options)
            val failCount = results.sumOf { it.failures.size }
            val okDevices = results.flatMap { it.succeededDeviceIds }.toSet()
            val message = when {
                failCount == 0 -> {
                    val deviceLabel = if (options.size == 1) {
                        options.first().deviceName
                    } else {
                        "${options.size} devices"
                    }
                    if (sources.size == 1) {
                        "Sent ${sources.first().fileName} to $deviceLabel"
                    } else {
                        "Sent ${sources.size} files to $deviceLabel"
                    }
                }
                okDevices.isEmpty() -> "Send failed for all destinations"
                else -> "Send finished with $failCount error(s)"
            }
            val status = if (okDevices.isEmpty() && failCount > 0) STATUS_FAILED else STATUS_DONE
            writeJob(job.copy(status = status, message = message))
            cleanupStaging(jobId)
            println("DesktopSendHandoff: $status — $message")
        }.onFailure { error ->
            val message = error.message ?: "Send failed"
            writeJob(job.copy(status = STATUS_FAILED, message = message))
            println("DesktopSendHandoff: failed $jobId :: $message")
        }
    }

    private fun cleanupStaging(jobId: String) {
        val staging = File(supportDir, "send-staging/$jobId")
        if (staging.isDirectory) {
            staging.deleteRecursively()
        }
    }

    private fun writeJob(job: SendJobFile) {
        jobFile(job.id).writeText(json.encodeToString(job))
    }

    private fun jobFile(jobId: String): File = File(jobsDir, "$jobId.json")

    private fun parseJobId(uri: URI): String? {
        if (uri.scheme != "omninode") return null
        val query = uri.rawQuery ?: uri.query ?: return null
        return query.split('&')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = part.substring(0, idx)
                val value = part.substring(idx + 1)
                key to value
            }
            .firstOrNull { it.first == "job" }
            ?.second
            ?.takeIf { it.isNotBlank() }
    }

    const val STATUS_PENDING = "pending"
    const val STATUS_RUNNING = "running"
    const val STATUS_DONE = "done"
    const val STATUS_FAILED = "failed"
}

@Serializable
data class SendJobFile(
    val id: String = UUID.randomUUID().toString(),
    val filePaths: List<String> = emptyList(),
    val deviceIds: List<String> = emptyList(),
    val status: String = DesktopSendHandoff.STATUS_PENDING,
    val message: String? = null
)
