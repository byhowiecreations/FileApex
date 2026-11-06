package com.fileapex.cli

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.seal.SealedText
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
    private const val PURPOSE = "cli-device-aliases"

    /** Tests point this at a temp folder. */
    @Volatile
    internal var directory: (() -> File)? = null

    private val aliasFile: File
        get() = File(directory?.invoke() ?: DesktopPlatformPaths.applicationSupportDirectory(), "cli_device_aliases.json")

    @Synchronized
    fun load(): AliasConfig {
        val text = SealedText.readFile(aliasFile, PURPOSE)
        if (text.isEmpty()) return AliasConfig()
        return runCatching { json.decodeFromString<AliasConfig>(text) }.getOrDefault(AliasConfig())
    }

    /** False when the alias could not be stored because the vault key is not available. */
    @Synchronized
    fun setAlias(deviceId: String, slug: String): Boolean {
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
        return SealedText.writeFile(aliasFile, PURPOSE, json.encodeToString(updated))
    }

    @Synchronized
    fun getAlias(deviceId: String): String? {
        return load().deviceIdToSlug[deviceId]
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
}
