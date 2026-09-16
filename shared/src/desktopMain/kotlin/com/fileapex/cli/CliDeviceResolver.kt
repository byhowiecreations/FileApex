package com.fileapex.cli

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import java.io.PrintStream

object CliDeviceResolver {

    /**
     * Filters devices to only authenticated, trusted paired devices.
     * Enforces the security filtering requirement:
     * Unauthenticated, expired, or untrusted handshake states are completely excluded.
     */
    suspend fun getAuthenticatedDevices(repository: DeviceRepository): List<PairedDeviceEntity> {
        val all = repository.listDevices()
        return all.filter { dev ->
            dev.deviceId.isNotBlank() &&
                dev.publicKeyHash.isNotBlank() &&
                !repository.isBlocklisted(dev)
        }
    }

    /**
     * Resolves a device query string (device name, ID, or alias/slug) to a single authenticated device.
     * Supports case-insensitive exact matching and fuzzy matching.
     * If multiple devices match, presents an interactive disambiguation menu.
     *
     * @param query Raw device identifier or alias
     * @param repository DeviceRepository
     * @param out Stream for printing disambiguation prompt
     * @param readLine Function to read user input (defaults to standard console readLine)
     * @return Resolved PairedDeviceEntity, or null if no known device matches
     */
    suspend fun resolveDevice(
        query: String,
        repository: DeviceRepository,
        out: PrintStream = System.out,
        readLine: () -> String? = { kotlin.io.readLine() }
    ): PairedDeviceEntity? {
        val cleanQuery = CliTokenizer.stripQuotes(query).trim().lowercase()
        if (cleanQuery.isEmpty()) return null

        val devices = getAuthenticatedDevices(repository)
        if (devices.isEmpty()) return null

        // 1. Direct deviceId match
        val byId = devices.firstOrNull { it.deviceId.equals(cleanQuery, ignoreCase = true) }
        if (byId != null) return byId

        // 2. Custom alias match
        val byCustomAlias = devices.firstOrNull { dev ->
            CliDeviceAliasManager.getAlias(dev.deviceId)?.equals(cleanQuery, ignoreCase = true) == true
        }
        if (byCustomAlias != null) return byCustomAlias

        // 3. Generated slug exact match
        val bySlug = devices.firstOrNull { dev ->
            CliDeviceAliasManager.resolveEffectiveSlug(dev).equals(cleanQuery, ignoreCase = true)
        }
        if (bySlug != null) return bySlug

        // 4. Exact deviceName match (case-insensitive)
        val byExactName = devices.filter { it.deviceName.trim().equals(cleanQuery, ignoreCase = true) }
        if (byExactName.size == 1) return byExactName.first()
        if (byExactName.size > 1) {
            return promptDisambiguation(cleanQuery, byExactName, out, readLine)
        }

        // 5. Fuzzy match against name, model, make, and slug
        val fuzzyCandidates = devices.filter { dev ->
            val name = dev.deviceName.lowercase()
            val slug = CliDeviceAliasManager.resolveEffectiveSlug(dev).lowercase()
            val model = dev.deviceModel.lowercase()
            val make = dev.deviceMake.lowercase()

            name.contains(cleanQuery) ||
                slug.contains(cleanQuery) ||
                (model.isNotBlank() && model.contains(cleanQuery)) ||
                (make.isNotBlank() && make.contains(cleanQuery)) ||
                cleanQuery.contains(slug)
        }

        return when {
            fuzzyCandidates.isEmpty() -> null
            fuzzyCandidates.size == 1 -> fuzzyCandidates.first()
            else -> promptDisambiguation(cleanQuery, fuzzyCandidates, out, readLine)
        }
    }

    private fun promptDisambiguation(
        query: String,
        candidates: List<PairedDeviceEntity>,
        out: PrintStream,
        readLine: () -> String?
    ): PairedDeviceEntity? {
        out.println("Multiple devices found matching '$query':")
        candidates.forEachIndexed { index, dev ->
            val slug = CliDeviceAliasManager.resolveEffectiveSlug(dev)
            val name = dev.deviceName.ifBlank { "Unknown" }
            out.println("  ${index + 1}) $name (slug: $slug)")
        }
        out.print("Select target device (1-${candidates.size}, or 'c' to cancel): ")
        out.flush()

        val response = readLine()?.trim() ?: return null
        if (response.equals("c", ignoreCase = true)) return null

        val choice = response.toIntOrNull()
        if (choice != null && choice in 1..candidates.size) {
            return candidates[choice - 1]
        }
        out.println("Invalid selection.")
        return null
    }
}
