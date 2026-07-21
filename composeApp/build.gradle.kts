import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.googleServices)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }

        androidMain.dependencies {
            implementation(libs.compose.ui.tooling)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.zxing.android.embedded)
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

android {
    namespace = "com.omninode"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    val appVersionName = providers.gradleProperty("omninode.version.name").get()

    defaultConfig {
        applicationId = "com.omninode"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("omninode.version.code").get().toInt()
        versionName = appVersionName
    }

    base {
        archivesName.set("OmniNode-$appVersionName")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // rules.md §15 — keystore at ~/AndroidStudioProjects/signed_files/{project}/;
    // credentials from KEYSTORE_PASSWORD / KEY_PASSWORD / KEY_ALIAS only.
    val releaseKeystoreDir = file("${System.getProperty("user.home")}/AndroidStudioProjects/signed_files/OmniNode")
    val releaseKeystoreFile = releaseKeystoreDir.listFiles()
        ?.firstOrNull { it.isFile && it.extension.equals("jks", ignoreCase = true) }
    val envStorePassword = providers.environmentVariable("KEYSTORE_PASSWORD")
    val envKeyPassword = providers.environmentVariable("KEY_PASSWORD")
    val envKeyAlias = providers.environmentVariable("KEY_ALIAS")
    val canSignRelease = releaseKeystoreFile != null &&
        envStorePassword.isPresent &&
        envKeyPassword.isPresent &&
        envKeyAlias.isPresent

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = envStorePassword.get()
                keyAlias = envKeyAlias.get()
                keyPassword = envKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.omninode.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "OmniNode"
            // jpackage macOS requires MAJOR > 0 and digits-only (no 0.0.6a).
            // Marketing version stays omninode.version.name; DMG is renamed on copy.
            packageVersion = "1.0.${providers.gradleProperty("omninode.version.code").get()}"

            macOS {
                bundleID = "com.omninode"
                // Empty entitlements — no App Sandbox (avoids TCC "access data from other apps").
                entitlementsFile.set(project.file("macos/OmniNode.entitlements"))
                runtimeEntitlementsFile.set(project.file("macos/OmniNode.entitlements"))
                // Prompt for Local Network so phones can reach this Mac share server.
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>OmniNode shares files with paired phones and computers on your local network.</string>
                        <key>NSBonjourServices</key>
                        <array>
                            <string>_http._tcp</string>
                        </array>
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>com.omninode</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>omninode</string>
                                    <string>omni</string>
                                </array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
            }
        }
    }
}

tasks.register("embedMacExtensions") {
    group = "distribution"
    description = "Build Share Extension and embed into OmniNode.app"
    dependsOn("createDistributable")
    doLast {
        val appBundle = layout.buildDirectory.dir("compose/binaries/main/app/OmniNode.app").get().asFile
        val script = rootProject.layout.projectDirectory.file("macos/scripts/embed_extensions.sh").asFile
        check(script.exists()) { "Missing ${script.absolutePath}" }
        val process = ProcessBuilder("bash", script.absolutePath, appBundle.absolutePath, "Release")
            .directory(rootProject.projectDir)
            .inheritIO()
            .start()
        val code = process.waitFor()
        // Non-zero is OK when Xcode is unavailable — script warns and exits 0;
        // treat hard failures as warnings so Android-only machines still build.
        if (code != 0) {
            logger.warn("embed_extensions.sh exited $code (extensions may be missing)")
        }
    }
}

tasks.matching { it.name == "packageDmg" || it.name == "packageReleaseDmg" }.configureEach {
    dependsOn("embedMacExtensions")
}

/** After [moveToCurrent], Gradle must rebuild when outputs no longer exist under `build/`. */
afterEvaluate {
    fun Project.apkOutputsPresent(variant: String): Boolean =
        apkOutputDir(variant).listFiles()?.any { it.isFile && it.extension == "apk" } == true

    listOf("assembleDebug", "packageDebug").forEach { taskName ->
        tasks.named(taskName).configure {
            outputs.upToDateWhen { apkOutputsPresent("debug") }
        }
    }

    listOf("assembleRelease", "packageRelease").forEach { taskName ->
        tasks.named(taskName).configure {
            outputs.upToDateWhen { apkOutputsPresent("release") }
        }
    }

    tasks.named("createDistributable").configure {
        outputs.upToDateWhen {
            distributableAppBundle().exists()
        }
    }

    tasks.matching { it.name == "packageDmg" || it.name == "packageReleaseDmg" }.configureEach {
        outputs.upToDateWhen {
            val dmgDir = layout.buildDirectory.dir("compose/binaries/main/dmg").get().asFile
            dmgDir.listFiles()?.any { it.isFile && it.extension.equals("dmg", ignoreCase = true) } == true
        }
    }
}

private fun Project.apkOutputDir(variant: String): File =
    layout.buildDirectory.dir("outputs/apk/$variant").get().asFile

private fun Project.distributableAppBundle(): File =
    layout.buildDirectory.dir("compose/binaries/main/app/OmniNode.app").get().asFile

/**
 * Moves build outputs into project-root `current/` (never copies — avoids duplicating large artifacts).
 */
private fun Project.currentBuildsDest(): File =
    rootProject.layout.projectDirectory.dir("current").asFile

private fun moveToCurrent(
    dest: File,
    source: File,
    destName: String = source.name,
    logger: org.gradle.api.logging.Logger
) {
    check(source.exists()) { "Missing build output: ${source.absolutePath}" }
    val target = dest.resolve(destName)
    Files.move(
        source.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING
    )
    ProcessBuilder("xattr", "-cr", target.absolutePath).start().waitFor()
    logger.lifecycle("Moved ${source.name} -> current/$destName")
}

/** Detach OmniNode installer volumes so `current/` can be replaced safely. */
private fun detachOmniNodeDmgVolumes() {
    val d = "$"
    ProcessBuilder(
        "bash",
        "-c",
        """
        for vol in /Volumes/OmniNode*; do
          [ -d "${d}vol" ] || continue
          hdiutil detach "${d}vol" -quiet 2>/dev/null || true
        done
        """.trimIndent()
    ).start().waitFor()
}

private fun prepareCurrentDirectory(
    dest: File,
    preserveDmgFiles: Boolean
) {
    if (!dest.exists()) {
        dest.mkdirs()
        return
    }
    dest.listFiles().orEmpty().forEach { entry ->
        if (preserveDmgFiles && entry.isFile && entry.extension.equals("dmg", ignoreCase = true)) {
            return@forEach
        }
        entry.deleteRecursively()
    }
}

private fun patchShippedAppMarketingVersion(dest: File, appVersionName: String, versionCode: String) {
    val shippedInfoPlist = dest.resolve("OmniNode.app/Contents/Info.plist")
    if (!shippedInfoPlist.exists()) return
    ProcessBuilder(
        "plutil", "-replace", "CFBundleShortVersionString",
        "-string", appVersionName, shippedInfoPlist.absolutePath
    ).start().waitFor()
    ProcessBuilder(
        "plutil", "-replace", "CFBundleVersion",
        "-string", versionCode, shippedInfoPlist.absolutePath
    ).start().waitFor()
}

private fun Project.embedMacExtensionsIn(appBundle: File) {
    val embedScript = rootProject.layout.projectDirectory.file("macos/scripts/embed_extensions.sh").asFile
    ProcessBuilder("bash", embedScript.absolutePath, appBundle.absolutePath, "Release")
        .directory(rootProject.projectDir)
        .inheritIO()
        .start()
        .waitFor()
}

private fun Project.shipToCurrent(
    includeDebugApk: Boolean,
    includeReleaseApk: Boolean,
    includeDmg: Boolean,
    mountDmg: Boolean,
    preserveExistingDmgOnWipe: Boolean
) {
    if (!preserveExistingDmgOnWipe) {
        detachOmniNodeDmgVolumes()
    }
    val dest = currentBuildsDest()
    prepareCurrentDirectory(dest, preserveDmgFiles = preserveExistingDmgOnWipe)

    val logger = logger
    val appVersionName = providers.gradleProperty("omninode.version.name").get()
    val versionCode = providers.gradleProperty("omninode.version.code").get()

    fun moveApksFrom(variant: String) {
        val apks = apkOutputDir(variant).listFiles().orEmpty().filter { it.isFile && it.extension == "apk" }
        check(apks.isNotEmpty()) { "No APK found in ${apkOutputDir(variant).absolutePath}" }
        apks.forEach { moveToCurrent(dest, it, logger = logger) }
    }
    if (includeDebugApk) moveApksFrom("debug")
    if (includeReleaseApk) moveApksFrom("release")

    val dmgDestName = "OmniNode-$appVersionName.dmg"
    if (includeDmg) {
        val dmgDir = layout.buildDirectory.dir("compose/binaries/main/dmg").get().asFile
        val dmgs = dmgDir.listFiles().orEmpty().filter { it.isFile && it.extension == "dmg" }
        check(dmgs.isNotEmpty()) { "No DMG found in ${dmgDir.absolutePath}" }
        dmgs.forEach { dmg ->
            moveToCurrent(dest, dmg, destName = dmgDestName, logger = logger)
        }
    }

    val buildAppBundle = distributableAppBundle()
    check(buildAppBundle.exists()) {
        "Missing build output: ${buildAppBundle.absolutePath}"
    }
    embedMacExtensionsIn(buildAppBundle)
    moveToCurrent(dest, buildAppBundle, logger = logger)

    patchShippedAppMarketingVersion(dest, appVersionName, versionCode)
    logger.lifecycle("Set OmniNode.app CFBundleShortVersionString=$appVersionName")

    val launchedBinary = dest.resolve("OmniNode.app/Contents/MacOS/OmniNode")
    check(launchedBinary.exists() && launchedBinary.canExecute()) {
        "OmniNode.app binary missing execute permission after move"
    }

    if (mountDmg) {
        val shippedDmg = dest.resolve(dmgDestName)
        check(shippedDmg.exists()) { "Missing DMG to mount: ${shippedDmg.absolutePath}" }
        ProcessBuilder("open", shippedDmg.absolutePath).start().waitFor()
        logger.lifecycle("Mounted $dmgDestName for manual install (left attached)")
    }
}

/**
 * Post-[assembleDebug]: debug APK + Mac .app into `current/`.
 * Does **not** build or mount a DMG (avoids hdiutil attach/detach during iteration).
 */
tasks.register("copyCurrentBuilds") {
    group = "distribution"
    description = "Move debug APK and Mac .app into root current/ (no DMG)"
    dependsOn("assembleDebug", "embedMacExtensions")

    doLast {
        shipToCurrent(
            includeDebugApk = true,
            includeReleaseApk = false,
            includeDmg = false,
            mountDmg = false,
            preserveExistingDmgOnWipe = true
        )
    }
}

/**
 * Final release ship: release APK, Mac .app, and DMG into `current/`, then mount the DMG once
 * for drag-to-Applications (left attached — no detach).
 */
tasks.register("copyReleaseBuilds") {
    group = "distribution"
    description = "Release APK + Mac .app + DMG into current/, then mount the DMG for install"
    dependsOn("assembleRelease", "embedMacExtensions", "packageDmg")

    doLast {
        shipToCurrent(
            includeDebugApk = false,
            includeReleaseApk = true,
            includeDmg = true,
            mountDmg = true,
            preserveExistingDmgOnWipe = false
        )
    }
}

/**
 * Full ship after [assembleDebug] + [assembleRelease]: all APKs, .app, and DMG into `current/`,
 * then mount the release DMG once. Use this instead of running [copyCurrentBuilds] and
 * [copyReleaseBuilds] back-to-back (they would fight over the same .app bundle).
 */
tasks.register("copyAllBuilds") {
    group = "distribution"
    description = "Move debug+release APKs, Mac .app, and DMG into current/, then mount DMG"
    dependsOn("assembleDebug", "assembleRelease", "embedMacExtensions", "packageDmg")

    doLast {
        shipToCurrent(
            includeDebugApk = true,
            includeReleaseApk = true,
            includeDmg = true,
            mountDmg = true,
            preserveExistingDmgOnWipe = false
        )
    }
}

