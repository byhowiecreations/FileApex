package com.fileapex.data.device

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.platform.DesktopPlatformPaths
import java.io.File

/**
 * If the current roster DB was reset empty, try importing paired rows from older
 * FileApex database files that may still exist on disk.
 */
internal object DesktopRosterRecovery {
    suspend fun importLegacyRosterIfEmpty(repository: DeviceRepository) {
        if (repository.listDevices().isNotEmpty()) return

        for (candidate in DesktopPlatformPaths.legacyRosterRecoveryCandidates()) {
            if (!candidate.isFile) continue
            val imported = runCatching { importPairedDevices(candidate, repository) }
                .getOrElse { error ->
                    println("DesktopRosterRecovery: skip ${candidate.path} - ${error.message}")
                    0
                }
            if (imported > 0) {
                println(
                    "DesktopRosterRecovery: restored $imported paired device(s) " +
                        "from ${candidate.path}"
                )
                return
            }
        }
    }

    private suspend fun importPairedDevices(source: File, repository: DeviceRepository): Int {
        val driver = BundledSQLiteDriver()
        val connection = driver.open(source.absolutePath)
        return try {
            var count = 0
            connection.prepare(
                """
                SELECT deviceId, deviceName, lastKnownIp, port, publicKeyHash, rootPath
                FROM paired_devices
                """.trimIndent()
            ).use { statement ->
                while (statement.step()) {
                    val entity = PairedDeviceEntity(
                        deviceId = statement.getText(0),
                        deviceName = statement.getText(1),
                        lastKnownIp = statement.getText(2),
                        port = statement.getInt(3),
                        publicKeyHash = statement.getText(4),
                        rootPath = statement.getText(5)
                    )
                    if (repository.adoptFromPairing(entity)) {
                        count++
                    }
                }
            }
            count
        } finally {
            connection.close()
        }
    }
}
