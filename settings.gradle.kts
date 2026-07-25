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

syncPlatformSdkDir()

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
