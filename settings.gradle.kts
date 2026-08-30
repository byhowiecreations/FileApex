rootProject.name = "FileApex"

fun java.util.Properties.platformValue(baseKey: String): String? {
    val os = System.getProperty("os.name").lowercase()
    val suffix = when {
        os.contains("windows") -> "windows"
        os.contains("mac") -> "macos"
        else -> "linux"
    }
    return getProperty("$baseKey.$suffix")?.trim()?.takeIf { it.isNotEmpty() }
}

fun syncPlatformSdkDir() {
    val localPropsFile = settingsDir.resolve("local.properties")
    if (!localPropsFile.isFile) return
    val localProps = java.util.Properties().apply {
        localPropsFile.inputStream().use { load(it) }
    }
    val platformSdkDir = localProps.platformValue("sdk.dir") ?: return
    if (localProps.getProperty("sdk.dir") == platformSdkDir) return
    localProps.setProperty("sdk.dir", platformSdkDir)
    localPropsFile.outputStream().use {
        localProps.store(it, "Synced sdk.dir from sdk.dir.<platform> in local.properties")
    }
}

fun loadFileApexVersion(settingsDir: java.io.File): Triple<String, String, String> {
    val versionFile = settingsDir.resolve("version.md")
    check(versionFile.isFile) { "Missing version.md at ${versionFile.absolutePath}" }
    val map = versionFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .associate { line ->
            val parts = line.split("=", limit = 2)
            check(parts.size == 2) { "Invalid version.md line (expected key=value): $line" }
            parts[0].trim() to parts[1].trim()
        }
    val name = map["name"]?.takeIf { it.isNotEmpty() }
        ?: error("version.md missing name=")
    val code = map["code"]?.takeIf { it.isNotEmpty() }
        ?: error("version.md missing code=")
    check(code.toIntOrNull() != null) { "version.md code must be an integer, was: $code" }
    val extensionVersion = map["extension_version"]?.takeIf { it.isNotEmpty() }
        ?: error("version.md missing extension_version=")
    check(firefoxExtensionVersionValid(extensionVersion)) {
        "version.md extension_version must be 1-4 dot-separated integers without leading zeros, was: $extensionVersion"
    }
    return Triple(name, code, extensionVersion)
}

/** Firefox AMO: up to four numeric segments, no leading zeros (1.0.1 not 1.01). */
private fun firefoxExtensionVersionValid(version: String): Boolean {
    val parts = version.split('.')
    if (parts.isEmpty() || parts.size > 4) return false
    return parts.all { part ->
        part.matches(Regex("""(0|[1-9]\d{0,8})"""))
    }
}

syncPlatformSdkDir()

val (fileapexVersionName, fileapexVersionCode, fileapexExtensionVersion) = loadFileApexVersion(settingsDir)

gradle.beforeProject {
    extensions.extraProperties.set("fileapex.version.name", fileapexVersionName)
    extensions.extraProperties.set("fileapex.version.code", fileapexVersionCode)
    extensions.extraProperties.set("fileapex.extension.version", fileapexExtensionVersion)
}

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":shared")
include(":composeApp")
