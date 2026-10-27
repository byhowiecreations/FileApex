package com.fileapex.domain.transfer

import com.fileapex.data.db.PendingTransferEntity
import com.fileapex.data.db.QueuedSourceSnapshot
import com.fileapex.data.db.QueuedTransferSourceKind
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Reads and rewrites the source lists a queued transfer stores as JSON. */
internal object QueuedTransferSources {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun sourceSummaryFromEntity(entity: PendingTransferEntity): String =
        when (runCatching { QueuedTransferSourceKind.valueOf(entity.sourceKind) }.getOrNull()) {
            QueuedTransferSourceKind.LocalRoots -> {
                val paths = runCatching {
                    json.decodeFromString(ListSerializer(String.serializer()), entity.sourceJson)
                }.getOrDefault(emptyList())
                pathSummary(paths)
            }
            QueuedTransferSourceKind.Sources -> {
                val snapshots = runCatching {
                    json.decodeFromString(ListSerializer(QueuedSourceSnapshot.serializer()), entity.sourceJson)
                }.getOrDefault(emptyList())
                fileSummary(snapshots.map { it.toSource() })
            }
            null -> entity.displayLabel
        }

    fun decodeDeviceIds(jsonPayload: String): List<String> =
        runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), jsonPayload)
        }.getOrDefault(emptyList())

    fun decodeSources(
        entity: PendingTransferEntity,
        persistHealed: (PendingTransferEntity) -> Unit
    ): List<MultiCopySource>? =
        when (runCatching { QueuedTransferSourceKind.valueOf(entity.sourceKind) }.getOrNull()) {
            QueuedTransferSourceKind.LocalRoots -> {
                val paths = runCatching {
                    json.decodeFromString(ListSerializer(String.serializer()), entity.sourceJson)
                }.getOrNull() ?: return null
                val healed = paths.map { healPathIfMissing(it) }
                if (healed != paths) {
                    persistHealed(
                        entity.copy(
                            sourceJson = json.encodeToString(ListSerializer(String.serializer()), healed),
                            lastError = null
                        )
                    )
                }
                LocalTransferTree.expandAbsolutePaths(healed).map { it as MultiCopySource }
            }
            QueuedTransferSourceKind.Sources -> {
                val snapshots = runCatching {
                    json.decodeFromString(ListSerializer(QueuedSourceSnapshot.serializer()), entity.sourceJson)
                }.getOrNull() ?: return null
                val healed = snapshots.map { healSnapshotIfMissing(it) }
                if (healed != snapshots) {
                    persistHealed(
                        entity.copy(
                            sourceJson = json.encodeToString(ListSerializer(QueuedSourceSnapshot.serializer()), healed),
                            lastError = null
                        )
                    )
                }
                healed.map { it.toSource() }
            }
            null -> null
        }

    /** Name of a queued local file that no longer matches what was queued (size or modified time), or null. */
    fun changedSourceName(entity: PendingTransferEntity): String? {
        if (runCatching { QueuedTransferSourceKind.valueOf(entity.sourceKind) }.getOrNull() !=
            QueuedTransferSourceKind.Sources
        ) {
            return null
        }
        val snapshots = runCatching {
            json.decodeFromString(ListSerializer(QueuedSourceSnapshot.serializer()), entity.sourceJson)
        }.getOrNull() ?: return null
        return snapshots.firstOrNull { snapshot ->
            if (!snapshot.remoteHost.isNullOrBlank() || snapshot.modifiedEpochMs <= 0L) return@firstOrNull false
            val file = java.io.File(snapshot.absolutePath)
            file.isFile && (file.lastModified() != snapshot.modifiedEpochMs || file.length() != snapshot.sizeBytes)
        }?.fileName
    }

    fun healPathIfMissing(rawPath: String): String {
        val path = Path(rawPath)
        if (SystemFileSystem.exists(path)) return rawPath
        val fileName = rawPath.substringAfterLast('/').substringAfterLast('\\')
        if (fileName.isBlank()) return rawPath
        val candidateDirs = listOf(
            "/storage/emulated/0/Pictures/Screenshots",
            "/storage/emulated/0/DCIM/Screenshots",
            "/storage/emulated/0/DCIM/Camera",
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Pictures",
            "/storage/emulated/0/Documents",
            "/sdcard/Pictures/Screenshots",
            "/sdcard/Download"
        )
        for (dir in candidateDirs) {
            val candidate = Path("$dir/$fileName")
            if (SystemFileSystem.exists(candidate)) {
                return candidate.toString()
            }
        }
        return rawPath
    }

    fun healSnapshotIfMissing(snapshot: QueuedSourceSnapshot): QueuedSourceSnapshot {
        val healedPath = healPathIfMissing(snapshot.absolutePath)
        if (healedPath != snapshot.absolutePath) {
            val size = runCatching { SystemFileSystem.metadataOrNull(Path(healedPath))?.size }.getOrNull()
            return snapshot.copy(
                absolutePath = healedPath,
                sizeBytes = size ?: snapshot.sizeBytes
            )
        }
        return snapshot
    }

    fun pathSummary(paths: List<String>): String =
        when (paths.size) {
            0 -> "Files"
            1 -> paths.first().substringAfterLast('/').substringAfterLast('\\')
            else -> "${paths.size} items"
        }

    fun fileSummary(sources: List<MultiCopySource>): String =
        when (sources.size) {
            0 -> "Files"
            1 -> sources.first().fileName
            else -> "${sources.size} files"
        }

    fun buildDisplayLabel(sourcePart: String, deviceNames: List<String>): String {
        val targetPart = when (deviceNames.size) {
            0 -> "devices"
            1 -> deviceNames.first()
            else -> "${deviceNames.size} devices"
        }
        return "$sourcePart → $targetPart"
    }

    fun absolutePathsFromEntity(entity: PendingTransferEntity): List<String> =
        when (runCatching { QueuedTransferSourceKind.valueOf(entity.sourceKind) }.getOrNull()) {
            QueuedTransferSourceKind.LocalRoots -> {
                runCatching {
                    json.decodeFromString(ListSerializer(String.serializer()), entity.sourceJson)
                }.getOrDefault(emptyList())
            }
            QueuedTransferSourceKind.Sources -> {
                runCatching {
                    json.decodeFromString(
                        ListSerializer(QueuedSourceSnapshot.serializer()),
                        entity.sourceJson
                    )
                }.getOrDefault(emptyList()).map { it.absolutePath }
            }
            null -> emptyList()
        }

    fun MultiCopySource.toSnapshot(): QueuedSourceSnapshot =
        when (this) {
            is MultiCopySource.Local -> QueuedSourceSnapshot(
                fileName = fileName,
                sizeBytes = sizeBytes,
                absolutePath = absolutePath,
                relativeDestPath = relativeDestPath,
                modifiedEpochMs = java.io.File(absolutePath).lastModified().coerceAtLeast(0L)
            )
            is MultiCopySource.Remote -> QueuedSourceSnapshot(
                fileName = fileName,
                sizeBytes = sizeBytes,
                absolutePath = absolutePath,
                relativeDestPath = relativeDestPath,
                remoteHost = host,
                remotePort = port
            )
        }

    fun QueuedSourceSnapshot.toSource(): MultiCopySource {
        val host = remoteHost?.trim().orEmpty()
        return if (host.isNotEmpty() && remotePort != null) {
            MultiCopySource.Remote(
                fileName = fileName,
                sizeBytes = sizeBytes,
                absolutePath = absolutePath,
                host = host,
                port = remotePort,
                relativeDestPath = relativeDestPath
            )
        } else {
            MultiCopySource.Local(
                fileName = fileName,
                sizeBytes = sizeBytes,
                absolutePath = absolutePath,
                relativeDestPath = relativeDestPath
            )
        }
    }
}
