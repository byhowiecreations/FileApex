package com.fileapex.platform

import java.io.File
import java.util.concurrent.TimeUnit

actual fun defaultStorageRoot(): String {
    return System.getProperty("user.home") ?: "/"
}

actual fun platformDeviceName(): String {
    val host = desktopHostName()
    return when {
        DesktopPlatformPaths.isMacOs() -> macComputerName() ?: host.ifBlank { "Mac" }
        DesktopPlatformPaths.isWindows() -> if (host.isBlank()) "Windows PC" else "Windows ($host)"
        DesktopPlatformPaths.isLinux() -> {
            val distro = linuxDistroName()
            if (host.isBlank() || host.equals("localhost", ignoreCase = true)) distro else "$distro ($host)"
        }
        else -> host.ifBlank { "Desktop" }
    }
}

private fun desktopHostName(): String {
    val fromEnv = System.getenv("COMPUTERNAME")?.trim().orEmpty()
    if (fromEnv.isNotEmpty()) return fromEnv
    val fromFile = runCatching { File("/etc/hostname").readText().trim() }.getOrDefault("")
    if (fromFile.isNotEmpty()) return fromFile
    return System.getenv("HOSTNAME")?.trim().orEmpty()
}

private fun macComputerName(): String? = runCatching {
    val process = ProcessBuilder("scutil", "--get", "ComputerName").redirectErrorStream(true).start()
    if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching null
    }
    process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
}.getOrNull()

private fun linuxDistroName(): String {
    val line = runCatching { File("/etc/os-release").readLines() }.getOrDefault(emptyList())
        .firstOrNull { it.startsWith("NAME=") }
    val name = line?.substringAfter('=')?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() } ?: return "Linux"
    return name.removeSuffix(" GNU/Linux").removeSuffix(" Linux").ifBlank { name }
}
