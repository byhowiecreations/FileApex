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

fun loadFileApexVersion(settingsDir: java.io.File): Pair<String, String> {
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
    return name to code
}

syncPlatformSdkDir()

val (fileapexVersionName, fileapexVersionCode) = loadFileApexVersion(settingsDir)

gradle.beforeProject {
    extensions.extraProperties.set("fileapex.version.name", fileapexVersionName)
    extensions.extraProperties.set("fileapex.version.code", fileapexVersionCode)
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
