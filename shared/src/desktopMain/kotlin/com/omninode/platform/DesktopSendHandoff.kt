package com.omninode.platform

import com.omninode.di.OmniNodeServices
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Finder Sync / Share Extension shell: write a pending job + open `omninode://send?job=…`.
 *
 * Transfer bytes are never started here. The main app’s [com.omninode.domain.transfer.TransferManager]
 * runs the same outbound Multi Copy path as in-app send.
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

    private val processMutex = Mutex()
    private val inFlight = mutableSetOf<String>()
    private val processorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var processorStarted = false

    private val supportDir: File
        get() = File(
            System.getProperty("user.home"),
            "Library/Application Support/com.omninode"
        )

    private val jobsDir: File
        get() = File(supportDir, "send-jobs").also { it.mkdirs() }

    /**
     * Canonical deep-link URI for a send job — identical for Finder Sync and Share Extension.
     * Main app treats this like a single-top “resume send” launch (already-running or cold start).
     */
    fun sendJobUri(jobId: String): URI =
        URI("omninode", /* authority */ "send", /* path */ null, /* query */ "job=$jobId", /* fragment */ null)

    fun installOpenUriHandler() {
        runCatching {
            if (!Desktop.isDesktopSupported()) return
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.APP_OPEN_URI)) return
            desktop.setOpenURIHandler { event ->
                val uri = event.uri
                if (handleOAuthCallback(uri)) return@setOpenURIHandler
                parseJobId(uri)?.let { jobId ->
                    println("DesktopSendHandoff: open URI job=$jobId")
                    enqueueJob(jobId)
                }
            }
            println("DesktopSendHandoff: APP_OPEN_URI handler installed")
        }.onFailure { error ->
            println("DesktopSendHandoff: URI handler not available :: ${error.message}")
        }
    }

    /**
     * Starts the lifecycle-aware job runner once services are initialized.
     * Safe to call multiple times; must run after [OmniNodeServices.init].
     * Independent of Compose UI so Finder Sync does not wait on window composition.
     */
    fun startJobProcessor() {
        if (processorStarted) return
        processorStarted = true
        processorScope.launch {
            val transferManager = OmniNodeServices.transferManager
            runCatching { transferManager.awaitReady() }
                .onFailure { error ->
                    println("DesktopSendHandoff: TransferManager not ready :: ${error.message}")
                    return@launch
                }
            println("DesktopSendHandoff: TransferManager ready — draining send jobs")
            val pending = flow {
                listPendingJobIds().forEach { emit(it) }
            }
            merge(pending, incomingJobIds).collect { jobId ->
                processJob(jobId)
            }
        }
    }

    fun enqueueJob(jobId: String) {
        _incomingJobIds.tryEmit(jobId)
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

    suspend fun processJob(jobId: String) {
        processMutex.withLock {
            if (!inFlight.add(jobId)) return
        }
        try {
            withContext(Dispatchers.IO) {
                processJobUnlocked(jobId)
            }
        } finally {
            processMutex.withLock { inFlight.remove(jobId) }
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

        val transferManager = OmniNodeServices.transferManager
        runCatching { transferManager.awaitReady() }
            .onFailure { error ->
                val message = "OmniNode not ready: ${error.message ?: "initialization incomplete"}"
                writeJob(job.copy(status = STATUS_FAILED, message = message))
                println("DesktopSendHandoff: $jobId :: $message")
                return
            }

        writeJob(job.copy(status = STATUS_RUNNING, message = "Sending…"))
        runCatching {
            val batch = transferManager.sendLocalPathsToDeviceIds(
                absolutePaths = job.filePaths,
                deviceIds = job.deviceIds
            )
            val status = if (batch.allFailed) STATUS_FAILED else STATUS_DONE
            writeJob(job.copy(status = status, message = batch.summaryMessage))
            if (!batch.allFailed) {
                cleanupStaging(jobId)
            }
            println("DesktopSendHandoff: $status — ${batch.summaryMessage}")
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

    private fun handleOAuthCallback(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "omni" && scheme != "omninode") return false
        if (uri.host != "oauth-callback") return false
        val params = parseQuery(uri)
        val error = params["error"]
        val code = params["code"]
        val state = params["state"]
        DesktopOAuthCallbacks.emit(
            OAuthCodeResult(
                code = code,
                state = state,
                error = error
            )
        )
        println("DesktopSendHandoff: OAuth callback received")
        return true
    }

    private fun parseQuery(uri: URI): Map<String, String> {
        val query = uri.rawQuery ?: uri.query ?: return emptyMap()
        return query.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = java.net.URLDecoder.decode(part.substring(0, idx), Charsets.UTF_8)
            val value = java.net.URLDecoder.decode(part.substring(idx + 1), Charsets.UTF_8)
            key to value
        }.toMap()
    }

    private fun parseJobId(uri: URI): String? {
        if (uri.scheme != "omninode") return null
        return parseQuery(uri)["job"]?.takeIf { it.isNotBlank() }
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
