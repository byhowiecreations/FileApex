package com.fileapex.cli

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.platform.DesktopPlatformPaths
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AliasConfig(
    /** Map of lowercase short slug -> deviceId */
    val slugToDeviceId: Map<String, String> = emptyMap(),
    /** Map of deviceId -> primary user alias */
    val deviceIdToSlug: Map<String, String> = emptyMap()
)

object CliDeviceAliasManager {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val aliasFile: File
        get() = File(DesktopPlatformPaths.applicationSupportDirectory(), "cli_device_aliases.json")

    @Synchronized
    fun load(): AliasConfig {
        return runCatching {
            val file = aliasFile
            if (file.exists()) {
                json.decodeFromString<AliasConfig>(file.readText())
            } else {
                AliasConfig()
            }
        }.getOrDefault(AliasConfig())
    }

    @Synchronized
    fun setAlias(deviceId: String, slug: String) {
        val cleanSlug = slug.trim().lowercase()
        require(cleanSlug.isNotEmpty()) { "Alias slug cannot be empty" }
        val current = load()
        val nextSlugToId = current.slugToDeviceId.toMutableMap()
        val nextIdToSlug = current.deviceIdToSlug.toMutableMap()

        // Remove any previous slug pointing to this device if desired
        val previousSlug = nextIdToSlug[deviceId]
        if (previousSlug != null) {
            nextSlugToId.remove(previousSlug)
        }

        nextSlugToId[cleanSlug] = deviceId
        nextIdToSlug[deviceId] = cleanSlug

        val updated = AliasConfig(
            slugToDeviceId = nextSlugToId,
            deviceIdToSlug = nextIdToSlug
        )
        save(updated)
    }

    @Synchronized
    fun getAlias(deviceId: String): String? {
        return load().deviceIdToSlug[deviceId]
    }

    @Synchronized
    fun getDeviceIdBySlug(slug: String): String? {
        return load().slugToDeviceId[slug.trim().lowercase()]
    }

    /**
     * Returns the effective slug for a device:
     * User-defined alias if set, otherwise auto-generated slug from device name/model.
     */
    fun resolveEffectiveSlug(device: PairedDeviceEntity): String {
        val custom = getAlias(device.deviceId)
        if (!custom.isNullOrBlank()) return custom
        return generateDefaultSlug(device)
    }

    /**
     * Generates a concise, lowercase, hyphenated slug from device name / make / model.
     */
    fun generateDefaultSlug(device: PairedDeviceEntity): String {
        val base = device.deviceName.ifBlank {
            listOf(device.deviceMake, device.deviceModel).filter { it.isNotBlank() }.joinToString(" ")
        }.ifBlank { device.deviceId.take(8) }

        val slug = base.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return slug.ifBlank { device.deviceId.take(8) }
    }

    @Synchronized
    private fun save(config: AliasConfig) {
        runCatching {
            val file = aliasFile
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            file.writeText(json.encodeToString(config))
        }
    }
}
