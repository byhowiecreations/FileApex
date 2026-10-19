package com.fileapex.platform

import java.io.File
import java.util.concurrent.TimeUnit

/** macOS hardware UUID, Windows MachineGuid, Linux machine-id. */
actual fun localHardwareIdRaw(): String = when (DesktopPlatformPaths.desktopOs) {
    DesktopPlatformPaths.DesktopOs.MacOs ->
        HardwareId.parseIoregUuid(run("/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice"))
    DesktopPlatformPaths.DesktopOs.Windows ->
        HardwareId.parseRegMachineGuid(run("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid"))
    DesktopPlatformPaths.DesktopOs.Linux,
    DesktopPlatformPaths.DesktopOs.Other ->
        listOf("/etc/machine-id", "/var/lib/dbus/machine-id").firstNotNullOfOrNull { path ->
            runCatching { File(path).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        }.orEmpty()
}

private fun run(vararg args: String): String = runCatching {
    val process = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) output else ""
}.getOrDefault("")
