package com.fileapex.domain.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One-way folder backup to a single paired device. Nothing is ever deleted on either side. */
@Serializable
data class BackupConfig(
    val enabled: Boolean = false,
    val destinationDeviceId: String = "",
    val sources: List<String> = emptyList(),
    val intervalHours: Int = DEFAULT_INTERVAL_HOURS,
    val wifiOnly: Boolean = true,
) {
    /** Enough set up for a run to do anything. */
    val isRunnable: Boolean get() = enabled && destinationDeviceId.isNotBlank() && sources.isNotEmpty()

    /** Adds [path] unless it, or a folder containing it, is already a source; folders it contains are folded into it. */
    fun withSource(path: String): BackupConfig {
        val clean = path.trimEnd('/')
        if (clean.isBlank() || sources.any { clean == it || clean.startsWith("$it/") }) return this
        return copy(sources = sources.filterNot { it.startsWith("$clean/") } + clean)
    }

    fun withoutSource(path: String): BackupConfig = copy(sources = sources - path)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 6
        val intervalChoices = listOf(1, 6, 24)

        private val json = Json { ignoreUnknownKeys = true }

        fun decode(raw: String): BackupConfig =
            if (raw.isBlank()) BackupConfig() else runCatching { json.decodeFromString(serializer(), raw) }.getOrDefault(BackupConfig())
    }
}
