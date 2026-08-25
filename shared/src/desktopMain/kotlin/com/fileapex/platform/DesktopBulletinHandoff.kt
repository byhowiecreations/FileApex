package com.fileapex.platform

import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import java.io.File
import kotlin.text.Charsets
import java.net.URI
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

/** Share Extension → `fileapex://bulletin?job=…` → [com.fileapex.data.bulletin.BulletinBoardSyncEngine]. */
object DesktopBulletinHandoff {
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

    private val jobsDir: File
        get() = File(DesktopPlatformPaths.applicationSupportDirectory(), "bulletin-jobs")

    fun parseBulletinJobId(uri: URI): String? {
        if (uri.scheme != "fileapex") return null
        if (uri.host != "bulletin") return null
        val query = uri.rawQuery ?: uri.query ?: return null
        return query.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = java.net.URLDecoder.decode(part.substring(0, idx), Charsets.UTF_8)
            val value = java.net.URLDecoder.decode(part.substring(idx + 1), Charsets.UTF_8)
            if (key == "job") value else null
        }.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    fun enqueueJob(jobId: String) {
        _incomingJobIds.tryEmit(jobId)
    }

    fun startJobProcessor() {
        if (processorStarted) return
        processorStarted = true
        processorScope.launch {
            val pending = flow {
                listPendingJobIds().forEach { emit(it) }
            }
            merge(pending, incomingJobIds).collect { jobId ->
                processJob(jobId)
            }
        }
    }

    fun listPendingJobIds(): List<String> {
        return jobsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.mapNotNull { file ->
                runCatching {
                    val job = json.decodeFromString<BulletinJobFile>(file.readText(Charsets.UTF_8))
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
        if (!file.isFile) return
        val job = runCatching {
            json.decodeFromString<BulletinJobFile>(file.readText(Charsets.UTF_8))
        }.getOrElse {
            return
        }
        if (job.status == STATUS_DONE || job.status == STATUS_FAILED) return

        val text = job.sharedText?.trim().orEmpty()
        val paths = job.filePaths.filter { it.isNotBlank() }
        if (text.isEmpty() && paths.isEmpty()) {
            writeJob(job.copy(status = STATUS_FAILED, message = AppI18n.t("nothing_to_post")))
            return
        }

        runCatching {
            val engine = FileApexServices.bulletinSyncEngine
            when {
                paths.isNotEmpty() -> {
                    val path = paths.first()
                    val staged = File(path)
                    engine.ingestSharedFile(
                        absolutePath = path,
                        fileName = staged.name,
                        sizeBytes = staged.length(),
                        caption = text
                    )
                }
                text.isNotEmpty() -> engine.ingestSharedText(text)
            }
            writeJob(job.copy(status = STATUS_DONE, message = AppI18n.t("posted_to_bulletin")))
            cleanupStaging(jobId)
        }.onFailure { error ->
            writeJob(job.copy(status = STATUS_FAILED, message = error.message ?: AppI18n.t("could_not_post_bulletin")))
        }
    }

    private fun cleanupStaging(jobId: String) {
        val staging = File(DesktopPlatformPaths.applicationSupportDirectory(), "send-staging/$jobId")
        if (staging.isDirectory) {
            staging.deleteRecursively()
        }
    }

    private fun writeJob(job: BulletinJobFile) {
        jobsDir.mkdirs()
        jobFile(job.id).writeText(json.encodeToString(job), Charsets.UTF_8)
    }

    private fun jobFile(jobId: String): File = File(jobsDir, "$jobId.json")

    const val STATUS_PENDING = "pending"
    const val STATUS_DONE = "done"
    const val STATUS_FAILED = "failed"
}

@Serializable
data class BulletinJobFile(
    val id: String,
    val sharedText: String? = null,
    val filePaths: List<String> = emptyList(),
    val status: String = DesktopBulletinHandoff.STATUS_PENDING,
    val message: String? = null
)
