package com.fileapex.platform

import com.fileapex.util.sha256Hex

/** The operating system's own id for this install or machine, or blank when it has none. Never logged, never sent raw. */
expect fun localHardwareIdRaw(): String

/**
 * A salted hash of the OS machine id (Android `ANDROID_ID`, macOS hardware UUID, Windows `MachineGuid`, Linux
 * `/etc/machine-id`). It survives an app reinstall, so a paired device can tell "same hardware, new keys". It is a hint
 * only, never proof of identity, and only paired devices over TLS and the host at pairing see it. rules.md: hardware
 * identifiers are never logged; neither the raw id nor this hash may be.
 */
object HardwareId {
    private const val SALT = "fileapex-machine|"

    fun hash(raw: String): String {
        val id = raw.trim().lowercase()
        return if (id.isEmpty()) "" else sha256Hex((SALT + id).encodeToByteArray())
    }

    /** This device's hash; blank when the platform gives no id. Computed once. */
    val local: String by lazy { runCatching { hash(localHardwareIdRaw()) }.getOrDefault("") }

    fun parseIoregUuid(output: String): String =
        Regex("\"IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\"").find(output)?.groupValues?.get(1).orEmpty()

    fun parseRegMachineGuid(output: String): String =
        Regex("MachineGuid\\s+REG_SZ\\s+(\\S+)").find(output)?.groupValues?.get(1).orEmpty()
}
