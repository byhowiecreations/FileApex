package com.fileapex.domain.backup

import com.fileapex.platform.backupStateDirectory
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A local file that could be backed up. */
data class BackupCandidate(
    val absolutePath: String,
    /** Path below the backup root, starting with the source folder's own name. */
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
)

/**
 * What has already reached one destination, keyed by local path. A file is only sent again when its
 * size or modified time differs from what was recorded, so unchanged files are never repeated.
 */
class BackupManifest private constructor(
    private val file: Path?,
    private val entries: MutableMap<String, String>,
) {
    fun isCurrent(candidate: BackupCandidate): Boolean = entries[candidate.absolutePath] == stamp(candidate)

    fun wasSentBefore(candidate: BackupCandidate): Boolean = candidate.absolutePath in entries

    fun record(candidate: BackupCandidate) {
        entries[candidate.absolutePath] = stamp(candidate)
    }

    fun recordRun(atMillis: Long, uploaded: Int, failed: Int, lastError: String? = null) {
        entries[LAST_RUN_KEY] = "$atMillis:$uploaded:$failed"
        if (lastError == null) entries.remove(LAST_ERROR_KEY) else entries[LAST_ERROR_KEY] = lastError
    }

    /** Why the most recent failed upload failed, when the last run had failures. */
    val lastError: String? get() = entries[LAST_ERROR_KEY]

    val lastRun: LastRun?
        get() = entries[LAST_RUN_KEY]?.split(':')?.takeIf { it.size == 3 }?.let {
            LastRun(it[0].toLongOrNull() ?: return null, it[1].toIntOrNull() ?: 0, it[2].toIntOrNull() ?: 0)
        }

    fun save() {
        val target = file ?: return
        runCatching {
            target.parent?.let { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.sink(target).buffered().use { sink ->
                sink.writeString(json.encodeToString(serializer, entries))
            }
        }
    }

    data class LastRun(val atMillis: Long, val uploaded: Int, val failed: Int)

    companion object {
        private const val LAST_RUN_KEY = "__last_run__"
        private const val LAST_ERROR_KEY = "__last_error__"
        private val json = Json
        private val serializer = MapSerializer(String.serializer(), String.serializer())

        private fun stamp(c: BackupCandidate) = "${c.sizeBytes}:${c.modifiedMillis}"

        fun inMemory(): BackupManifest = BackupManifest(null, mutableMapOf())

        fun load(destinationDeviceId: String): BackupManifest {
            val safeId = destinationDeviceId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            val path = Path(backupStateDirectory(), "manifest-$safeId.json")
            val entries = runCatching {
                if (!SystemFileSystem.exists(path)) return@runCatching mutableMapOf<String, String>()
                val text = SystemFileSystem.source(path).buffered().use { it.readString() }
                json.decodeFromString(serializer, text).toMutableMap()
            }.getOrDefault(mutableMapOf())
            return BackupManifest(path, entries)
        }
    }
}

/** Files that still need sending, split by whether the destination already holds an older copy. */
class BackupPlan(val newFiles: List<BackupCandidate>, val changedFiles: List<BackupCandidate>) {
    val total: Int get() = newFiles.size + changedFiles.size

    companion object {
        fun build(candidates: List<BackupCandidate>, manifest: BackupManifest): BackupPlan {
            val fresh = ArrayList<BackupCandidate>()
            val changed = ArrayList<BackupCandidate>()
            for (candidate in candidates) {
                when {
                    manifest.isCurrent(candidate) -> Unit
                    manifest.wasSentBefore(candidate) -> changed += candidate
                    else -> fresh += candidate
                }
            }
            return BackupPlan(fresh, changed)
        }
    }
}
