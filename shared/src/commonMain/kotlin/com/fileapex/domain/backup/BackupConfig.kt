package com.fileapex.domain.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.fileapex.util.cancellableCatching

/** One-way folder backup to a single paired device. Nothing is ever deleted on either side. */
@Serializable
data class BackupConfig(
    val enabled: Boolean = false,
    val destinationDeviceId: String = "",
    val sources: List<String> = emptyList(),
    val intervalHours: Int = DEFAULT_INTERVAL_HOURS,
    val wifiOnly: Boolean = true,
    val promptDismissed: Boolean = false,
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
            if (raw.isBlank()) BackupConfig() else cancellableCatching("BackupConfig: json.decodeFromString") { json.decodeFromString(serializer(), raw) }.getOrDefault(BackupConfig())
    }
}

/** Progressive disclosure state for Backup & Sync settings sections. */
data class BackupPageVisibility(
    val showDestination: Boolean,
    val shouldAutoExpandDestination: Boolean,
    val showFolders: Boolean,
    val showFrequencyAndOptions: Boolean
) {
    companion object {
        fun from(config: BackupConfig): BackupPageVisibility {
            val hasDestination = config.enabled && config.destinationDeviceId.isNotBlank()
            return BackupPageVisibility(
                showDestination = config.enabled,
                shouldAutoExpandDestination = config.enabled && config.destinationDeviceId.isBlank(),
                showFolders = hasDestination,
                showFrequencyAndOptions = hasDestination && config.sources.isNotEmpty()
            )
        }
    }
}
