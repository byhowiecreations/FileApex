import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private fun isMacHost(): Boolean =
    System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

private fun isWindowsHost(): Boolean =
    System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)

/**
 * Register only the installer format for the current OS so Gradle never schedules
 * Mac DMG work on Windows (or MSI work on macOS).
 */
private fun desktopInstallerFormats(): Array<TargetFormat> = when {
    isMacHost() -> arrayOf(TargetFormat.Dmg)
    isWindowsHost() -> arrayOf(TargetFormat.Exe)
    else -> emptyArray()
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.googleServices)
}

// Loaded from committed version.md via settings.gradle.kts (beforeProject).
val fileapexVersionName = extra["fileapex.version.name"] as String
val fileapexVersionCode = extra["fileapex.version.code"] as String

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
    namespace = "com.fileapex"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.fileapex"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = fileapexVersionCode.toInt()
        versionName = fileapexVersionName
    }

    base {
        archivesName.set("FileApex-v$fileapexVersionName")
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
            pickFirsts += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    // rules.md §15 — keystore at ~/AndroidStudioProjects/signed_files/{project}/;
    // credentials from KEYSTORE_PASSWORD / KEY_PASSWORD / KEY_ALIAS only.
    val releaseKeystoreDir = file("${System.getProperty("user.home")}/AndroidStudioProjects/signed_files/FileApex")
    val releaseKeystoreFileName = "fileapex-release.jks"
    val releaseKeystoreFile = releaseKeystoreDir.resolve(releaseKeystoreFileName).takeIf { it.isFile }
    val envStorePassword = providers.environmentVariable("KEYSTORE_PASSWORD")
    val envKeyPassword = providers.environmentVariable("KEY_PASSWORD")
    val envKeyAlias = providers.environmentVariable("KEY_ALIAS")
    val resolvedReleaseAlias = if (releaseKeystoreFile != null && envStorePassword.isPresent) {
        resolveReleaseKeyAlias(releaseKeystoreFile, envStorePassword.get(), envKeyAlias.orNull)
    } else {
        null
    }
    val canSignRelease = releaseKeystoreFile != null &&
        envStorePassword.isPresent &&
        envKeyPassword.isPresent &&
        resolvedReleaseAlias != null

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = envStorePassword.get()
                keyAlias = resolvedReleaseAlias!!
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

tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Fail unless FileApex release keystore and signing env vars are configured"
    doLast {
        val keystoreDir = file("${System.getProperty("user.home")}/AndroidStudioProjects/signed_files/FileApex")
        val keystoreFile = keystoreDir.resolve("fileapex-release.jks").takeIf { it.isFile }
        val missing = buildList {
            if (!keystoreDir.isDirectory) {
                add("Keystore directory not found: ${keystoreDir.absolutePath}")
            } else if (keystoreFile == null) {
                add("Release keystore not found: ${keystoreDir.resolve("fileapex-release.jks").absolutePath}")
            }
            if (!providers.environmentVariable("KEYSTORE_PASSWORD").isPresent) {
                add("KEYSTORE_PASSWORD environment variable is not set")
            }
            if (!providers.environmentVariable("KEY_PASSWORD").isPresent) {
                add("KEY_PASSWORD environment variable is not set")
            }
            if (keystoreFile != null &&
                providers.environmentVariable("KEYSTORE_PASSWORD").isPresent &&
                resolveReleaseKeyAlias(
                    keystoreFile,
                    providers.environmentVariable("KEYSTORE_PASSWORD").get(),
                    providers.environmentVariable("KEY_ALIAS").orNull,
                ).isBlank()
            ) {
                add("No signing alias found in fileapex-release.jks")
            }
        }
        if (missing.isNotEmpty()) {
            throw org.gradle.api.GradleException(
                "Release signing is required but not configured. Fix the following, then rebuild:\n" +
                    missing.joinToString("\n") { "  • $it" }
            )
        }
        val alias = resolveReleaseKeyAlias(
            keystoreFile!!,
            providers.environmentVariable("KEYSTORE_PASSWORD").get(),
            providers.environmentVariable("KEY_ALIAS").orNull,
        )
        logger.lifecycle("Release signing: ${keystoreFile.absolutePath} (alias: $alias)")
    }
}

tasks.register("verifyReleaseApkSigned") {
    group = "verification"
    description = "Fail if assembleRelease produced an unsigned APK (unsigned artifacts are deleted)"
    dependsOn("assembleRelease")
    doLast {
        val dir = apkOutputDir("release")
        val apks = dir.listFiles().orEmpty().filter { it.isFile && it.extension == "apk" }
        check(apks.isNotEmpty()) { "No release APK found in ${dir.absolutePath}" }
        val unsigned = apks.filter { it.name.contains("unsigned", ignoreCase = true) }
        if (unsigned.isNotEmpty()) {
            unsigned.forEach { artifact ->
                if (artifact.delete()) {
                    logger.lifecycle("Removed unsigned release artifact: ${artifact.name}")
                }
            }
            throw org.gradle.api.GradleException(
                "Release APK signing failed — unsigned artifact(s) removed. " +
                    "Configure ~/AndroidStudioProjects/signed_files/FileApex/*.jks and " +
                    "KEYSTORE_PASSWORD / KEY_PASSWORD / KEY_ALIAS, then rebuild."
            )
        }
    }
}

tasks.register("printReleaseSha1") {
    group = "verification"
    description = "Print SHA-1 for fileapex-release.jks using KEY_ALIAS and KEYSTORE_PASSWORD"
    dependsOn("verifyReleaseSigning")
    doLast {
        val keystoreFile = file("${System.getProperty("user.home")}/AndroidStudioProjects/signed_files/FileApex/fileapex-release.jks")
        val storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
            ?: error("KEYSTORE_PASSWORD environment variable is not set")
        val configuredAlias = providers.environmentVariable("KEY_ALIAS").orNull
        val alias = resolveReleaseKeyAlias(keystoreFile, storePassword, configuredAlias)
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        exec {
            commandLine(
                "keytool",
                "-list",
                "-v",
                "-keystore",
                keystoreFile.absolutePath,
                "-alias",
                alias,
                "-storepass",
                storePassword,
            )
            standardOutput = output
            errorOutput = errors
            isIgnoreExitValue = true
        }
        val combined = output.toString() + errors.toString()
        if (combined.contains("keytool error", ignoreCase = true)) {
            error(
                "Could not read SHA-1 from ${keystoreFile.name}. Check KEYSTORE_PASSWORD.\n${combined.trim()}"
            )
        }
        val sha1 = Regex("""SHA1:\s*([0-9A-Fa-f:]+)""")
            .find(output.toString())
            ?.groupValues
            ?.get(1)
            ?.uppercase()
            ?: error("Could not parse SHA-1 from keytool output")
        logger.lifecycle("Release keystore: ${keystoreFile.absolutePath}")
        logger.lifecycle("Release alias: $alias")
        if (configuredAlias != null && configuredAlias != alias) {
            logger.lifecycle("Configured KEY_ALIAS='$configuredAlias' does not exist; using '$alias'")
        }
        logger.lifecycle("Release SHA-1: $sha1")
    }
}

fun org.gradle.api.Project.resolveReleaseKeyAlias(
    keystoreFile: java.io.File,
    storePassword: String,
    configuredAlias: String?,
): String {
    if (!configuredAlias.isNullOrBlank()) {
        val probe = ByteArrayOutputStream()
        val probeErrors = ByteArrayOutputStream()
        exec {
            commandLine(
                "keytool",
                "-list",
                "-v",
                "-keystore",
                keystoreFile.absolutePath,
                "-alias",
                configuredAlias,
                "-storepass",
                storePassword,
            )
            standardOutput = probe
            errorOutput = probeErrors
            isIgnoreExitValue = true
        }
        if (!(probe.toString() + probeErrors.toString()).contains("keytool error", ignoreCase = true)) {
            return configuredAlias
        }
    }
    val listing = ByteArrayOutputStream()
    val listingErrors = ByteArrayOutputStream()
    exec {
        commandLine(
            "keytool",
            "-list",
            "-keystore",
            keystoreFile.absolutePath,
            "-storepass",
            storePassword,
        )
        standardOutput = listing
        errorOutput = listingErrors
        isIgnoreExitValue = true
    }
    val combined = listing.toString() + listingErrors.toString()
    if (combined.contains("keytool error", ignoreCase = true)) {
        error("Could not inspect ${keystoreFile.name}. Check KEYSTORE_PASSWORD.\n${combined.trim()}")
    }
    val alias = Regex("""^([^\s,]+),\s""", RegexOption.MULTILINE)
        .findAll(listing.toString())
        .map { it.groupValues[1] }
        .firstOrNull()
        ?: error("No key aliases found in ${keystoreFile.name}")
    if (!configuredAlias.isNullOrBlank() && configuredAlias != alias) {
        logger.warn(
            "KEY_ALIAS='$configuredAlias' not found in ${keystoreFile.name}; release signing will use '$alias'. " +
                "Update KEY_ALIAS to match the new keystore."
        )
    }
    return alias
}

afterEvaluate {
    listOf("assembleRelease", "packageRelease", "bundleRelease").forEach { taskName ->
        tasks.matching { it.name == taskName }.configureEach {
            dependsOn("verifyReleaseSigning")
        }
    }
    listOf("copyReleaseBuilds", "copyAllBuilds", "copyWindowsReleaseBuilds").forEach { taskName ->
        tasks.matching { it.name == taskName }.configureEach {
            dependsOn("verifyReleaseSigning", "verifyReleaseApkSigned")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.fileapex.MainKt"

        jvmArgs += listOf(
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
            "-Djava.awt.headless=false",
        )
        if (isMacHost()) {
            jvmArgs += listOf(
                "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            )
        }
        if (isWindowsHost()) {
            // Belt-and-suspenders for packaged/run launches; [DesktopJvmStartup] also sets this in main().
            jvmArgs += listOf("-Dskiko.renderApi=OPENGL")
        }

        buildTypes.release.proguard {
            obfuscate.set(false)
            configurationFiles.from(project.file("proguard-desktop.pro"))
        }

        nativeDistributions {
            targetFormats(*desktopInstallerFormats())
            packageName = "FileApex"
            vendor = "ByHowieCreations"
            description = "FileApex"
            appResourcesRootDir.set(project.file("windows/jpackage-resources"))
            // jpackage macOS requires MAJOR > 0 and digits-only (no 0.0.6a).
            // Marketing version stays version.md name=; installers are renamed on copy.
            packageVersion = "1.0.$fileapexVersionCode"
            includeAllModules = true

            // Pin the bundled JRE to the correct architecture for each Mac build type.
            // The Intel packageIntelDmg task sets JAVA_HOME to jdk-21-x64 before invoking Gradle,
            // so the env-var branch covers the x86_64 case. The arm64 branch prevents the Compose
            // plugin from auto-discovering jdk-21-x64 alphabetically and bundling an x86_64 JRE
            // into the Silicon .app / DMG.
            val envJavaHome = System.getenv("JAVA_HOME")
            val arm64Jdk = File(System.getProperty("user.home"), ".jdks/jdk-21.0.11+10/Contents/Home")
            val x64Jdk  = File(System.getProperty("user.home"), ".jdks/jdk-21-x64/Contents/Home")
            val pinnedJdk = when {
                // Intel build: JAVA_HOME already points at the x64 JDK.
                !envJavaHome.isNullOrBlank() && envJavaHome.contains("x64") && x64Jdk.isDirectory -> x64Jdk
                // Silicon build: always use the arm64 JDK when it exists.
                arm64Jdk.isDirectory -> arm64Jdk
                // Fallback: let Gradle decide (non-Mac hosts, CI, etc.).
                else -> null
            }
            if (pinnedJdk != null) {
                javaHome = pinnedJdk.absolutePath
            }

            macOS {
                iconFile.set(project.file("icons/FileApex.icns"))


                bundleID = "com.fileapex"
                // Empty entitlements — no App Sandbox (avoids TCC "access data from other apps").
                entitlementsFile.set(project.file("macos/FileApex.entitlements"))
                runtimeEntitlementsFile.set(project.file("macos/FileApex.entitlements"))
                // Prompt for Local Network so phones can reach this Mac share server.
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>FileApex communicates directly with local devices over your network to sync files and manage node discovery.</string>
                        <key>NSAppTransportSecurity</key>
                        <dict>
                            <key>NSAllowsLocalNetworking</key>
                            <true/>
                            <key>NSAllowsArbitraryLoads</key>
                            <true/>
                        </dict>
                        <key>NSBonjourServices</key>
                        <array>
                            <string>_fileapex._tcp</string>
                            <string>_fileapex-ln._tcp</string>
                        </array>
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>com.fileapex</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>fileapex</string>
                                    <string>omni</string>
                                </array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
            }

            windows {
                iconFile.set(project.file("icons/FileApex.ico"))
                menuGroup = "FileApex"
                menu = true
                dirChooser = true
                shortcut = false
                // Stable upgrade UUID — required for in-place MSI upgrades across releases.
                // msiPackageVersion (1.0.${code}) must increase each Windows MSI ship; marketing name stays separate.
                upgradeUuid = "7c4f8a2e-9b1d-4e6a-c3f5-8d2e1a0b9c7f"
                msiPackageVersion = "1.0.$fileapexVersionCode"
            }
        }
    }
}

tasks.register("buildMacTrayBridge") {
    group = "distribution"
    description = "Compile libFileApexTray.dylib (NSStatusItem + NSPopover)"
    onlyIf { isMacHost() }
    doLast {
        val script = rootProject.layout.projectDirectory.file("macos/scripts/build_tray_bridge.sh").asFile
        if (!script.exists()) {
            logger.warn("Missing ${script.absolutePath}")
            return@doLast
        }
        val process = ProcessBuilder("bash", script.absolutePath)
            .directory(rootProject.projectDir)
            .inheritIO()
            .start()
        val code = process.waitFor()
        if (code != 0) {
            logger.warn("build_tray_bridge.sh exited $code (tray may be disabled)")
        }
    }
}

tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    if (isMacHost()) {
        dependsOn("buildMacTrayBridge")
    }
}

tasks.matching { it.name == "createRuntimeImage" }.configureEach {
    doLast {
        if (!isMacHost()) return@doLast
        val stagingRuntime = layout.buildDirectory.dir("compose/tmp/main/runtime").get().asFile
        val libjli = stagingRuntime.resolve("lib/libjli.dylib")
        if (!libjli.exists()) return@doLast

        // Determine which arch this build needs.
        val envJavaHome = System.getenv("JAVA_HOME") ?: ""
        val needsArch = if (envJavaHome.contains("x64")) "x86_64" else "arm64"

        // Determine what jlink actually produced.
        val fileProc = ProcessBuilder("file", libjli.absolutePath).start()
        val fileOut = fileProc.inputStream.bufferedReader().readText()
        fileProc.waitFor()
        val producedArch = if (fileOut.contains("x86_64")) "x86_64" else "arm64"

        if (producedArch == needsArch) {
            logger.lifecycle("Runtime image arch: $producedArch ✓")
            return@doLast
        }

        // Wrong arch — rebuild using the correct JDK's jlink directly.
        logger.lifecycle("Runtime image is $producedArch but need $needsArch — rebuilding with correct jlink")
        val correctJdkHome = when (needsArch) {
            "arm64"  -> File(System.getProperty("user.home"), ".jdks/jdk-21.0.11+10/Contents/Home")
            "x86_64" -> File(System.getProperty("user.home"), ".jdks/jdk-21-x64/Contents/Home")
            else -> null
        }
        checkNotNull(correctJdkHome) { "Cannot determine correct JDK home for arch $needsArch" }
        check(correctJdkHome.isDirectory) { "JDK not found at ${correctJdkHome.absolutePath}" }

        val jlink   = File(correctJdkHome, "bin/jlink")
        val jmodsDir = File(correctJdkHome, "jmods")
        check(jlink.canExecute())  { "jlink not executable at ${jlink.absolutePath}" }
        check(jmodsDir.isDirectory) { "jmods not found at ${jmodsDir.absolutePath}" }

        stagingRuntime.deleteRecursively()
        val jlinkResult = ProcessBuilder(
            jlink.absolutePath,
            "--module-path", jmodsDir.absolutePath,
            "--add-modules", "ALL-MODULE-PATH",
            "--no-header-files", "--no-man-pages",
            "--compress=1", "--strip-debug",
            "--output", stagingRuntime.absolutePath
        ).inheritIO().start().waitFor()
        check(jlinkResult == 0) { "jlink failed with exit code $jlinkResult" }
        logger.lifecycle("Runtime image rebuilt as $needsArch ✓")
    }
}

private fun Project.embedMacTrayBridgeIn(appBundle: File) {
    if (!isMacHost()) {
        logger.lifecycle("Skipping Mac tray dylib embed — not a macOS build host")
        return
    }
    val dylib = rootProject.layout.projectDirectory.file("macos/build/Tray/libFileApexTray.dylib").asFile
    if (!dylib.isFile) {
        logger.warn("libFileApexTray.dylib missing — menu bar tray disabled")
        return
    }
    val frameworksDir = appBundle.resolve("Contents/Frameworks")
    frameworksDir.mkdirs()
    val dest = frameworksDir.resolve("libFileApexTray.dylib")
    Files.copy(dylib.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
    ProcessBuilder("/usr/bin/codesign", "--force", "--sign", "-", dest.absolutePath)
        .inheritIO()
        .start()
        .waitFor()
    logger.lifecycle("Embedded native tray bridge at ${dest.absolutePath}")
}

private fun Project.patchMacRuntimeLocalNetworkPlist(appBundle: File) {
    if (!isMacHost()) return
    val runtimePlist = appBundle.resolve("Contents/runtime/Contents/Info.plist")
    if (!runtimePlist.isFile) {
        logger.warn("Nested JRE Info.plist missing — skipped Local Network keys")
        return
    }
    val description =
        "FileApex communicates directly with local devices over your network to sync files and manage node discovery."
    fun buddy(vararg args: String): Int =
        ProcessBuilder("/usr/libexec/PlistBuddy", *args, runtimePlist.absolutePath)
            .redirectErrorStream(true)
            .start()
            .waitFor()
    buddy("-c", "Delete :NSLocalNetworkUsageDescription")
    buddy("-c", "Add :NSLocalNetworkUsageDescription string \"$description\"")
    buddy("-c", "Delete :NSBonjourServices")
    buddy("-c", "Add :NSBonjourServices array")
    buddy("-c", "Add :NSBonjourServices:0 string _fileapex._tcp")
    buddy("-c", "Add :NSBonjourServices:1 string _fileapex-ln._tcp")
    buddy("-c", "Delete :NSAppTransportSecurity")
    buddy("-c", "Add :NSAppTransportSecurity dict")
    buddy("-c", "Add :NSAppTransportSecurity:NSAllowsLocalNetworking bool true")
    buddy("-c", "Add :NSAppTransportSecurity:NSAllowsArbitraryLoads bool true")
    logger.lifecycle("Patched nested JRE Info.plist with Local Network + Bonjour keys")
}

tasks.register("embedMacExtensions") {
    group = "distribution"
    description = "Build Share Extension and embed into FileApex.app"
    dependsOn("createDistributable")
    onlyIf { isMacHost() }
    doLast {
        val appBundle = layout.buildDirectory.dir("compose/binaries/main/app/FileApex.app").get().asFile
        embedMacTrayBridgeIn(appBundle)
        patchMacRuntimeLocalNetworkPlist(appBundle)
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
        val uuidScript = rootProject.layout.projectDirectory.file("macos/scripts/unique_main_uuid.sh").asFile
        check(uuidScript.isFile) { "Missing ${uuidScript.absolutePath}" }
        val uuidCode = ProcessBuilder("bash", uuidScript.absolutePath, appBundle.absolutePath)
            .directory(rootProject.projectDir)
            .inheritIO()
            .start()
            .waitFor()
        check(uuidCode == 0) { "unique_main_uuid.sh exited $uuidCode — Local Network UUID patch failed" }
    }
}

tasks.matching { it.name == "packageDmg" || it.name == "packageReleaseDmg" }.configureEach {
    if (isMacHost()) {
        dependsOn("embedMacExtensions")
    }
}

/**
 * Runs a command, failing the build with its combined output if it exits non-zero.
 * Kept minimal (no plist parsing) by always driving hdiutil with an explicit `-mountpoint`.
 */
private fun runOrFail(vararg command: String) {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    check(code == 0) { "Command failed (${command.joinToString(" ")}), exit $code:\n$output" }
}

/**
 * Compose Desktop's DMG task (hdiutil create + osascript Finder styling) never sets a custom
 * volume icon. hdiutil then leaves whatever `.VolumeIcon.icns` happens to already be attached to
 * the source folder's Finder metadata, which resolves to the JDK's own generic `JavaApp.icns`
 * (jpackage's placeholder "Duke" icon) — so every mounted FileApex.dmg showed that instead of the
 * app icon. Fix: decompress the finished DMG to a writable image, drop our own icon in as
 * `.VolumeIcon.icns`, flag the volume as having a custom icon, then recompress in place.
 */
private fun Project.setDmgVolumeIcon(dmg: File) {
    val iconFile = project.file("icons/FileApex.icns")
    check(iconFile.isFile) { "Missing ${iconFile.absolutePath}" }

    val rwDmg = File(dmg.parentFile, "${dmg.nameWithoutExtension}-rw.dmg")
    rwDmg.delete()
    runOrFail("hdiutil", "convert", dmg.absolutePath, "-format", "UDRW", "-o", rwDmg.absolutePath)

    val mountPoint = Files.createTempDirectory("fileapex-dmg-icon-").toFile()
    mountPoint.delete()
    try {
        runOrFail("hdiutil", "attach", rwDmg.absolutePath, "-nobrowse", "-noautoopen", "-mountpoint", mountPoint.absolutePath)
        try {
            Files.copy(
                iconFile.toPath(),
                File(mountPoint, ".VolumeIcon.icns").toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            runOrFail("/usr/bin/SetFile", "-a", "C", mountPoint.absolutePath)
        } finally {
            runOrFail("hdiutil", "detach", mountPoint.absolutePath, "-quiet")
        }
    } finally {
        mountPoint.deleteRecursively()
    }

    check(dmg.delete()) { "Could not remove pre-icon-fix DMG at ${dmg.absolutePath}" }
    runOrFail("hdiutil", "convert", rwDmg.absolutePath, "-format", "UDZO", "-imagekey", "zlib-level=9", "-o", dmg.absolutePath)
    rwDmg.delete()
    logger.lifecycle("Set FileApex volume icon on ${dmg.name}")
}

/** Runs after [packageDmg] so `current/`-bound DMGs mount with the real FileApex icon, not jpackage's generic one. */
tasks.register("fixDmgVolumeIcon") {
    group = "distribution"
    description = "Replace the default JDK volume icon in packaged DMGs with FileApex's icon"
    dependsOn("packageDmg")
    onlyIf { isMacHost() }
    doLast {
        val dmgDir = layout.buildDirectory.dir("compose/binaries/main/dmg").get().asFile
        val dmgs = dmgDir.listFiles().orEmpty().filter { it.isFile && it.extension.equals("dmg", ignoreCase = true) }
        check(dmgs.isNotEmpty()) { "No DMG found in ${dmgDir.absolutePath}" }
        dmgs.forEach { dmg -> setDmgVolumeIcon(dmg) }
    }
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
            distributableDesktopApp().exists()
        }
    }

    tasks.matching { it.name == "packageDmg" || it.name == "packageReleaseDmg" }.configureEach {
        outputs.upToDateWhen {
            val dmgDir = layout.buildDirectory.dir("compose/binaries/main/dmg").get().asFile
            dmgDir.listFiles()?.any { it.isFile && it.extension.equals("dmg", ignoreCase = true) } == true
        }
    }

    tasks.matching { it.name == "packageMsi" || it.name == "packageReleaseMsi" }.configureEach {
        outputs.upToDateWhen {
            val release = name == "packageReleaseMsi"
            msiOutputDir(release).listFiles()?.any { it.isFile && it.extension.equals("msi", ignoreCase = true) } == true
        }
    }

    if (isWindowsHost()) {
        // Windows ships release EXE only — no debug app-image or debug installer.
        tasks.matching { it.name == "createDistributable" || it.name == "packageMsi" || it.name == "packageExe" }.configureEach {
            enabled = false
        }
    }

    tasks.named("assembleDebug").configure {
        finalizedBy("copyCurrentBuilds")
        dependsOn(":shared:verifyDesktopSmokeTestConfig")
    }
}

/**
 * Release MSI embeds the app-image; delete staging folders afterward so they are not left on disk.
 */
tasks.register("pruneWindowsAppImageStaging") {
    group = "distribution"
    description = "Remove Windows app-image staging dirs after MSI packaging (Windows host only)"
    onlyIf { isWindowsHost() }
    doLast {
        listOf(
            layout.buildDirectory.dir("compose/binaries/main-release/app").get().asFile,
            layout.buildDirectory.dir("compose/binaries/main/app").get().asFile,
        ).forEach { dir ->
            if (dir.exists()) {
                dir.deleteRecursively()
                logger.lifecycle("Pruned app-image staging dir ${dir.absolutePath}")
            }
        }
    }
}

tasks.matching { it.name == "packageReleaseMsi" }.configureEach {
    if (isWindowsHost()) {
        finalizedBy("pruneWindowsAppImageStaging")
    }
}

private fun releaseApkDestName(appVersionName: String): String =
    "FileApex-v$appVersionName.apk"

private fun debugApkDestName(appVersionName: String): String =
    "FileApex-v$appVersionName-debug.apk"

private fun Project.apkOutputDir(variant: String): File =
    layout.buildDirectory.dir("outputs/apk/$variant").get().asFile

private fun Project.distributableMacAppBundle(): File =
    layout.buildDirectory.dir("compose/binaries/main/app/FileApex.app").get().asFile

private fun Project.distributableWindowsAppDir(): File =
    layout.buildDirectory.dir("compose/binaries/main/app/FileApex").get().asFile

private fun Project.distributableDesktopApp(): File =
    if (isMacHost()) distributableMacAppBundle() else distributableWindowsAppDir()

private fun Project.msiOutputDir(release: Boolean = false): File =
    layout.buildDirectory.dir(
        if (release) "compose/binaries/main-release/msi" else "compose/binaries/main/msi"
    ).get().asFile

private fun Project.shipMsiToCurrent(
    dest: File,
    appVersionName: String,
    logger: org.gradle.api.logging.Logger,
    release: Boolean = false
) {
    val msiDir = msiOutputDir(release)
    val msis = msiDir.listFiles().orEmpty().filter { it.isFile && it.extension.equals("msi", ignoreCase = true) }
    check(msis.isNotEmpty()) {
        "No MSI found in ${msiDir.absolutePath}. Run ${if (release) "packageReleaseMsi" else "packageMsi"} on a Windows host with WiX installed."
    }
    val preferred = msis.maxByOrNull { it.lastModified() } ?: msis.first()
    patchMsiMetadata(preferred)
    val shippedFile = dest.resolve("FileApex-v$appVersionName.msi")
    moveToCurrent(dest, preferred, destName = "FileApex-v$appVersionName.msi", logger = logger)
    patchMsiMetadata(shippedFile)
}

private fun Project.exeOutputDir(release: Boolean = false): File =
    layout.buildDirectory.dir(
        if (release) "compose/binaries/main-release/exe" else "compose/binaries/main/exe"
    ).get().asFile

private fun Project.shipExeToCurrent(
    dest: File,
    appVersionName: String,
    logger: org.gradle.api.logging.Logger,
    release: Boolean = false
) {
    val exeDir = exeOutputDir(release)
    val exes = exeDir.listFiles().orEmpty().filter { it.isFile && it.extension.equals("exe", ignoreCase = true) }
    if (exes.isEmpty()) return
    val preferred = exes.maxByOrNull { it.lastModified() } ?: exes.first()
    moveToCurrent(dest, preferred, destName = "FileApex-v$appVersionName.exe", logger = logger)
}

private fun patchMsiMetadata(msiFile: File) {
    if (!isWindowsHost() || !msiFile.isFile) return
    runCatching {
        val path = msiFile.absolutePath.replace("\\", "/")
        val psScript = """
            ${'$'}msiPath = "$path"
            ${'$'}comObj = New-Object -ComObject WindowsInstaller.Installer
            ${'$'}database = ${'$'}comObj.GetType().InvokeMember("OpenDatabase", "InvokeMethod", ${'$'}null, ${'$'}comObj, @(${'$'}msiPath, 1))
            ${'$'}sumInfo = ${'$'}database.GetType().InvokeMember("SummaryInformation", "GetProperty", ${'$'}null, ${'$'}database, @(20))
            ${'$'}sumInfo.GetType().InvokeMember("Property", "SetProperty", ${'$'}null, ${'$'}sumInfo, @(2, "FileApex"))
            ${'$'}sumInfo.GetType().InvokeMember("Property", "SetProperty", ${'$'}null, ${'$'}sumInfo, @(4, "ByHowieCreations"))
            ${'$'}sumInfo.GetType().InvokeMember("Property", "SetProperty", ${'$'}null, ${'$'}sumInfo, @(6, "A local-first P2P file sharing app"))
            ${'$'}sumInfo.GetType().InvokeMember("Persist", "InvokeMethod", ${'$'}null, ${'$'}sumInfo, ${'$'}null)
            ${'$'}database.GetType().InvokeMember("Commit", "InvokeMethod", ${'$'}null, ${'$'}database, ${'$'}null)
        """.trimIndent()
        ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psScript)
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }
}

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
    if (target.exists()) {
        target.deleteRecursively()
    }
    Files.move(
        source.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING
    )
    if (isMacHost()) {
        ProcessBuilder("xattr", "-cr", target.absolutePath).start().waitFor()
        if (target.name.endsWith(".app")) {
            ProcessBuilder("codesign", "--force", "--deep", "--sign", "-", target.absolutePath).start().waitFor()
        }
    }
    logger.lifecycle("Moved ${source.name} -> current/$destName")
}



/** Detach FileApex installer volumes so `current/` can be replaced safely. */
private fun detachFileApexDmgVolumes() {
    if (!isMacHost()) return
    val d = "$"
    ProcessBuilder(
        "bash",
        "-c",
        """
        for vol in /Volumes/FileApex*; do
          [ -d "${d}vol" ] || continue
          hdiutil detach "${d}vol" -quiet 2>/dev/null || true
        done
        """.trimIndent()
    ).start().waitFor()
}

private fun prepareCurrentDirectory(
    dest: File,
    preserveDmgFiles: Boolean,
    preserveMsiFiles: Boolean = false
) {
    if (!dest.exists()) {
        dest.mkdirs()
        return
    }
    dest.listFiles().orEmpty().forEach { entry ->
        if (preserveDmgFiles && entry.isFile && entry.extension.equals("dmg", ignoreCase = true)) {
            return@forEach
        }
        if (preserveMsiFiles && entry.isFile && entry.extension.equals("msi", ignoreCase = true)) {
            return@forEach
        }
        entry.deleteRecursively()
    }
}

private fun patchShippedAppMarketingVersion(dest: File, appVersionName: String, versionCode: String) {
    val shippedInfoPlist = dest.resolve("FileApex.app/Contents/Info.plist")
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
    if (!isMacHost()) {
        logger.lifecycle("Skipping Mac extension embed — not a macOS build host")
        return
    }
    embedMacTrayBridgeIn(appBundle)
    patchMacRuntimeLocalNetworkPlist(appBundle)
    val embedScript = rootProject.layout.projectDirectory.file("macos/scripts/embed_extensions.sh").asFile
    ProcessBuilder("bash", embedScript.absolutePath, appBundle.absolutePath, "Release")
        .directory(rootProject.projectDir)
        .inheritIO()
        .start()
        .waitFor()
    val uuidScript = rootProject.layout.projectDirectory.file("macos/scripts/unique_main_uuid.sh").asFile
    check(uuidScript.isFile) { "Missing ${uuidScript.absolutePath}" }
    logger.lifecycle("Uniquing FileApex launcher Mach-O UUID for Local Network policy")
    val uuidCode = ProcessBuilder("bash", uuidScript.absolutePath, appBundle.absolutePath)
        .directory(rootProject.projectDir)
        .inheritIO()
        .start()
        .waitFor()
    check(uuidCode == 0) { "unique_main_uuid.sh exited $uuidCode — Local Network UUID patch failed" }
}

private fun Project.shipToCurrent(
    includeDebugApk: Boolean,
    includeReleaseApk: Boolean,
    includeDmg: Boolean,
    includeMsi: Boolean,
    mountDmg: Boolean,
    preserveExistingDmgOnWipe: Boolean,
    preserveExistingMsiOnWipe: Boolean = false
) {
    if (!preserveExistingDmgOnWipe) {
        detachFileApexDmgVolumes()
    }
    val dest = currentBuildsDest()
    prepareCurrentDirectory(
        dest,
        preserveDmgFiles = preserveExistingDmgOnWipe,
        preserveMsiFiles = preserveExistingMsiOnWipe
    )

    val logger = logger
    val appVersionName = fileapexVersionName
    val versionCode = fileapexVersionCode

    fun moveApksFrom(variant: String) {
        val apks = apkOutputDir(variant).listFiles().orEmpty().filter { it.isFile && it.extension == "apk" }
        check(apks.isNotEmpty()) { "No APK found in ${apkOutputDir(variant).absolutePath}" }
        apks.forEach { apk ->
            if (variant == "release" && apk.name.contains("unsigned", ignoreCase = true)) {
                error(
                    "Release APK is unsigned (${apk.name}) — not shipped. " +
                        "Configure ~/AndroidStudioProjects/signed_files/FileApex/*.jks and " +
                        "KEYSTORE_PASSWORD / KEY_PASSWORD / KEY_ALIAS, then run assembleRelease."
                )
            }
            val destName = when (variant) {
                "debug" -> debugApkDestName(appVersionName)
                "release" -> releaseApkDestName(appVersionName)
                else -> apk.name
            }
            moveToCurrent(dest, apk, destName = destName, logger = logger)
        }
    }
    if (includeDebugApk) moveApksFrom("debug")
    if (includeReleaseApk) moveApksFrom("release")

    if (includeDmg && isMacHost()) {
        val dmgDir = layout.buildDirectory.dir("compose/binaries/main/dmg").get().asFile
        val dmgs = dmgDir.listFiles().orEmpty().filter { it.isFile && it.extension.equals("dmg", ignoreCase = true) }
        check(dmgs.isNotEmpty()) { "No DMG found in ${dmgDir.absolutePath}" }
        dmgs.forEach { dmg ->
            val destName = when {
                dmg.name.contains("x86") || dmg.name.contains("x64") || dmg.name.contains("Intel") -> "FileApex-v$appVersionName-Intel.dmg"
                else -> "FileApex-v$appVersionName-Silicon.dmg"
            }
            moveToCurrent(dest, dmg, destName = destName, logger = logger)
        }
    }



    if (isWindowsHost()) {
        if (includeMsi) shipMsiToCurrent(dest, appVersionName, logger, release = true)
        shipExeToCurrent(dest, appVersionName, logger, release = true)
    }

    if (isMacHost()) {
        val buildAppBundle = distributableMacAppBundle()
        check(buildAppBundle.exists()) {
            "Missing build output: ${buildAppBundle.absolutePath}"
        }
        embedMacExtensionsIn(buildAppBundle)
        moveToCurrent(dest, buildAppBundle, logger = logger)

        patchShippedAppMarketingVersion(dest, appVersionName, versionCode)
        logger.lifecycle("Set FileApex.app CFBundleShortVersionString=$appVersionName")

        val launchedBinary = dest.resolve("FileApex.app/Contents/MacOS/FileApex")
        check(launchedBinary.exists() && launchedBinary.canExecute()) {
            "FileApex.app binary missing execute permission after move"
        }
    }

    if (mountDmg && isMacHost()) {
        val dmgs = dest.listFiles().orEmpty().filter { it.isFile && it.extension.equals("dmg", ignoreCase = true) }
        dmgs.forEach { shippedDmg ->
            ProcessBuilder("open", shippedDmg.absolutePath).start().waitFor()
            logger.lifecycle("Mounted ${shippedDmg.name} for manual install (left attached)")
        }
    }

}

/**
 * Post-[assembleDebug]: debug APK + desktop app into `current/` (Mac only).
 */
tasks.register("copyCurrentBuilds") {
    group = "distribution"
    description = "Move debug APK and Mac .app into root current/ (Mac host only)"
    onlyIf { isMacHost() }
    dependsOn("assembleDebug", "embedMacExtensions")

    doLast {
        shipToCurrent(
            includeDebugApk = true,
            includeReleaseApk = false,
            includeDmg = false,
            includeMsi = false,
            mountDmg = false,
            preserveExistingDmgOnWipe = true,
            preserveExistingMsiOnWipe = true
        )
    }
}

/**
 * Final release ship into `current/`. Mac: .app + DMG. Windows: release MSI only.
 */
tasks.register("copyReleaseBuilds") {
    group = "distribution"
    description = "Release APK + desktop ship into current/ (Mac .app+DMG or Windows EXE)"
    dependsOn("verifyReleaseApkSigned")
    if (isMacHost()) {
        dependsOn("embedMacExtensions", "fixDmgVolumeIcon")
    } else if (isWindowsHost()) {
        dependsOn("createReleaseDistributable", "packageInnoExe")
    }

    doLast {
        shipToCurrent(
            includeDebugApk = false,
            includeReleaseApk = apkOutputDir("release").listFiles()?.any { it.isFile && it.extension == "apk" } == true,
            includeDmg = isMacHost(),
            includeMsi = false,
            mountDmg = false,
            preserveExistingDmgOnWipe = false,
            preserveExistingMsiOnWipe = false
        )
    }
}

/**
 * Windows ship: release EXE into `current/` (no APK, no portable folder).
 */
tasks.register("copyWindowsBuilds") {
    group = "distribution"
    description = "Build release EXE and move to current/ (Windows host only)"
    dependsOn("createReleaseDistributable", "packageInnoExe")
    onlyIf { isWindowsHost() }

    doLast {
        shipToCurrent(
            includeDebugApk = false,
            includeReleaseApk = false,
            includeDmg = false,
            includeMsi = false,
            mountDmg = false,
            preserveExistingDmgOnWipe = true,
            preserveExistingMsiOnWipe = false
        )
    }
}

/**
 * Windows release ship: release APK + release EXE into `current/`.
 */
tasks.register("packageInnoExe") {
    group = "distribution"
    description = "Compile Inno Setup EXE installer using ISCC"
    dependsOn("createReleaseDistributable")
    onlyIf { isWindowsHost() }

    doLast {
        val userHome = System.getProperty("user.home")
        val isccExe = File(userHome, "AppData/Local/Programs/Inno Setup 6/ISCC.exe")
        val issFile = rootProject.file("windows/FileApex.iss")
        check(isccExe.exists()) { "ISCC.exe not found at ${isccExe.absolutePath}" }
        check(issFile.exists()) { "FileApex.iss not found at ${issFile.absolutePath}" }

        exec {
            commandLine(isccExe.absolutePath, "/DAppVersion=$fileapexVersionName", issFile.absolutePath)
        }
        logger.lifecycle("Successfully compiled Inno Setup installer into current/FileApex-v$fileapexVersionName.exe")

        // Prune app-image staging dirs so files are not left on disk
        listOf(
            layout.buildDirectory.dir("compose/binaries/main-release/app").get().asFile,
            layout.buildDirectory.dir("compose/binaries/main/app").get().asFile,
        ).forEach { dir ->
            if (dir.exists()) {
                dir.deleteRecursively()
                logger.lifecycle("Pruned app-image staging dir ${dir.absolutePath}")
            }
        }
    }
}

tasks.register("copyWindowsReleaseBuilds") {
    group = "distribution"
    description = "Release APK + release EXE into current/ (Windows host only)"
    dependsOn("verifyReleaseApkSigned", "createReleaseDistributable", "packageInnoExe")
    onlyIf { isWindowsHost() }

    doLast {
        shipToCurrent(
            includeDebugApk = false,
            includeReleaseApk = true,
            includeDmg = false,
            includeMsi = false,
            mountDmg = false,
            preserveExistingDmgOnWipe = true,
            preserveExistingMsiOnWipe = false
        )
    }
}

tasks.register("verifyDesktopPackagingTasks") {
    group = "verification"
    description = "Confirm Compose Desktop packaging tasks are registered for this host OS"
    doLast {
        val requiredTasks = buildList {
            add("packageDistributionForCurrentOS")
            if (isMacHost()) {
                add("createDistributable")
                add("packageDmg")
                add("packageReleaseDmg")
            }
            if (isWindowsHost()) {
                add("createReleaseDistributable")
                if (tasks.findByName("packageReleaseMsi") != null) {
                    add("packageReleaseMsi")
                } else {
                    add("packageInnoExe")
                }
            }
        }
        requiredTasks.forEach { taskName ->
            check(tasks.findByName(taskName) != null) { "Missing desktop packaging task: $taskName" }
        }
        logger.lifecycle(
            "Desktop packaging tasks registered (host=${System.getProperty("os.name")}). " +
                when {
                    isMacHost() -> "DMG packaging enabled; MSI tasks not registered on macOS."
                    isWindowsHost() -> "MSI packaging enabled; DMG tasks not registered on Windows."
                    else -> "No native installer format registered for this host."
                }
        )
    }
}

fun escapeJsonString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * Writes `composeApp/google-services.json` before the Google Services plugin runs.
 * Prefers `json/google-services.json` when present; otherwise generates from gradle.properties.
 * The output file is gitignored — never commit real credentials.
 */
val generateGoogleServicesJson = tasks.register("generateGoogleServicesJson") {
    group = "build setup"
    description = "Copy or generate composeApp/google-services.json from local config"
    val projectId = providers.gradleProperty("fileapex.firebase.project.id").orElse("")
    val apiKey = providers.gradleProperty("fileapex.firebase.api.key").orElse("")
    val applicationId = providers.gradleProperty("fileapex.firebase.application.id").orElse("")
    inputs.property("projectId", projectId)
    inputs.property("apiKey", apiKey)
    inputs.property("applicationId", applicationId)
    val sourceFile = rootProject.layout.projectDirectory.file("json/google-services.json")
    inputs.file(sourceFile).optional()
    val outFile = layout.projectDirectory.file("google-services.json")
    outputs.file(outFile)
    doLast {
        val source = sourceFile.asFile
        if (source.isFile) {
            source.copyTo(outFile.asFile, overwrite = true)
            logger.lifecycle("Copied google-services.json from ${source.absolutePath}")
            return@doLast
        }
        val appId = applicationId.get().trim()
        val pid = projectId.get().trim()
        val key = apiKey.get().trim()
        require(pid.isNotEmpty() && key.isNotEmpty() && appId.isNotEmpty()) {
            "Set fileapex.firebase.project.id, fileapex.firebase.api.key, and " +
                "fileapex.firebase.application.id in gradle.properties, or add json/google-services.json"
        }
        val projectNumber = appId.split(":").getOrNull(1)
            ?: error(
                "Invalid fileapex.firebase.application.id — expected format " +
                    "1:<project_number>:android:<app_hash>"
            )
        outFile.asFile.writeText(
            """
            |{
            |  "project_info": {
            |    "project_number": "$projectNumber",
            |    "project_id": "${escapeJsonString(pid)}",
            |    "storage_bucket": "${escapeJsonString(pid)}.firebasestorage.app"
            |  },
            |  "client": [
            |    {
            |      "client_info": {
            |        "mobilesdk_app_id": "${escapeJsonString(appId)}",
            |        "android_client_info": {
            |          "package_name": "com.fileapex"
            |        }
            |      },
            |      "oauth_client": [],
            |      "api_key": [
            |        {
            |          "current_key": "${escapeJsonString(key)}"
            |        }
            |      ],
            |      "services": {
            |        "appinvite_service": {
            |          "other_platform_oauth_client": []
            |        }
            |      }
            |    }
            |  ],
            |  "configuration_version": "1"
            |}
            """.trimMargin()
        )
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateGoogleServicesJson)
    dependsOn(
        ":shared:verifyGoogleOAuthProjectAlignment",
        ":shared:verifyFirebaseAndroidOAuthSetup",
    )
}

tasks.matching { it.name.startsWith("process") && it.name.endsWith("GoogleServices") }.configureEach {
    dependsOn(generateGoogleServicesJson)
}

/**
 * Silicon (arm64) DMG — spawns a fresh Gradle subprocess with JAVA_HOME explicitly set to the
 * arm64 JDK so the Compose plugin always bundles the correct JRE regardless of what the parent
 * daemon has cached.
 */
tasks.register("packageSiliconDmg") {
    group = "distribution"
    description = "Package Silicon arm64 Mac DMG — explicitly sets JAVA_HOME to arm64 JDK"
    onlyIf { isMacHost() }
    doLast {
        val arm64Jdk = File(System.getProperty("user.home"), ".jdks/jdk-21.0.11+10/Contents/Home")
        check(arm64Jdk.isDirectory) { "arm64 JDK not found at ${arm64Jdk.absolutePath}" }

        // Clear any stale x86_64 runtime that a cached checkRuntime task may have restored.
        val stagingAppDir = layout.buildDirectory.dir("compose/binaries/main/app").get().asFile
        val stagingRuntime = layout.buildDirectory.dir("compose/tmp/main/runtime").get().asFile
        if (stagingAppDir.exists()) stagingAppDir.deleteRecursively()
        if (stagingRuntime.exists()) stagingRuntime.deleteRecursively()

        val cmd = "source signing.local.env && unset JAVA_HOME && export JAVA_HOME='${arm64Jdk.absolutePath}' &&" +
            " ./gradlew --no-daemon packageDmg fixDmgVolumeIcon embedMacExtensions" +
            " -x assembleRelease -x verifyReleaseApkSigned -x verifyReleaseSigning"
        val exit = ProcessBuilder("bash", "-c", cmd)
            .directory(rootProject.projectDir)
            .inheritIO()
            .start().waitFor()
        check(exit == 0) { "Silicon DMG packaging failed with exit code $exit" }

        // Move the produced DMG and .app into current/.
        val dmgDir = layout.buildDirectory.dir("compose/binaries/main/dmg").get().asFile
        val siliconDmg = dmgDir.listFiles().orEmpty()
            .firstOrNull { it.isFile && it.extension.equals("dmg", ignoreCase = true) }
        val dest = currentBuildsDest()
        if (siliconDmg != null) {
            moveToCurrent(dest, siliconDmg, destName = "FileApex-v$fileapexVersionName-Silicon.dmg", logger = logger)
        }
        val stagingApp = stagingAppDir.resolve("FileApex.app")
        check(stagingApp.isDirectory) {
            "Silicon .app missing at ${stagingApp.absolutePath}"
        }
        val destApp = dest.resolve("FileApex.app")
        if (destApp.exists()) destApp.deleteRecursively()
        destApp.mkdirs()
        val rsync = ProcessBuilder(
            "rsync", "-a", "--delete",
            stagingApp.absolutePath + "/",
            destApp.absolutePath + "/"
        ).inheritIO().start().waitFor()
        check(rsync == 0) { "rsync of FileApex.app exited $rsync" }
        val plist = destApp.resolve("Contents/Info.plist")
        check(plist.isFile) { "FileApex.app missing Contents/Info.plist after rsync" }
        ProcessBuilder(
            "/usr/libexec/PlistBuddy", "-c",
            "Set :CFBundleShortVersionString $fileapexVersionName",
            plist.absolutePath
        ).inheritIO().start().waitFor()
        logger.lifecycle("Set FileApex.app CFBundleShortVersionString=$fileapexVersionName")
    }
}

/**
 * Intel (x86_64) DMG — spawns a fresh Gradle subprocess under Rosetta with JAVA_HOME set to the
 * x64 JDK.  Runs after packageSiliconDmg so the two builds never share a runtime staging dir.
 */
tasks.register("packageIntelDmg") {
    group = "distribution"
    description = "Package Intel x86_64 Mac DMG — explicitly sets JAVA_HOME to x64 JDK under Rosetta"
    onlyIf { isMacHost() }
    doLast {
        val x64Jdk = File(System.getProperty("user.home"), ".jdks/jdk-21-x64/Contents/Home")
        check(x64Jdk.isDirectory) { "x86_64 JDK not found at ${x64Jdk.absolutePath}" }

        // Clear any arm64 runtime left by packageSiliconDmg.
        val stagingApp     = layout.buildDirectory.dir("compose/binaries/main/app").get().asFile
        val stagingRuntime = layout.buildDirectory.dir("compose/tmp/main/runtime").get().asFile
        if (stagingApp.exists())     stagingApp.deleteRecursively()
        if (stagingRuntime.exists()) stagingRuntime.deleteRecursively()

        val cmd = "source signing.local.env && unset JAVA_HOME && export JAVA_HOME='${x64Jdk.absolutePath}' &&" +
            " ./gradlew --no-daemon packageDmg fixDmgVolumeIcon" +
            " -x assembleRelease -x verifyReleaseApkSigned -x verifyReleaseSigning"
        val exit = ProcessBuilder("arch", "-x86_64", "bash", "-c", cmd)
            .directory(rootProject.projectDir)
            .inheritIO()
            .start().waitFor()
        check(exit == 0) { "Intel DMG packaging failed with exit code $exit" }

        val dmgDir = layout.buildDirectory.dir("compose/binaries/main/dmg").get().asFile
        val x64Dmg = dmgDir.listFiles().orEmpty()
            .firstOrNull { it.isFile && it.extension.equals("dmg", ignoreCase = true) }
        if (x64Dmg != null) {
            moveToCurrent(
                currentBuildsDest(), x64Dmg,
                destName = "FileApex-v$fileapexVersionName-Intel.dmg",
                logger = logger
            )
        }
    }
}

/**
 * Full ship into `current/`.
 * Mac: release APK → packageSiliconDmg → packageIntelDmg (each with their own explicit JAVA_HOME).
 * Windows: release APK + MSI.
 */
tasks.register("copyAllBuilds") {
    group = "distribution"
    description = "Ship into current/ (Mac: APK + Silicon DMG + Intel DMG each with explicit JDK; Windows: APK + MSI)"
    if (isMacHost()) {
        // Only build the APK in-process; desktop DMGs are spawned as explicit subprocesses.
        dependsOn("assembleRelease", "verifyReleaseApkSigned")
        finalizedBy("packageSiliconDmg")
    } else if (isWindowsHost()) {
        dependsOn("verifyReleaseApkSigned", "createReleaseDistributable", "packageReleaseMsi")
    } else {
        dependsOn("assembleRelease", "verifyReleaseApkSigned")
    }

    doLast {
        // Ship the APK only; DMGs are handled by the Silicon/Intel subprocess tasks.
        shipToCurrent(
            includeDebugApk = false,
            includeReleaseApk = true,
            includeDmg = false,         // DMGs shipped by packageSiliconDmg / packageIntelDmg
            includeMsi = isWindowsHost(),
            mountDmg = false,
            preserveExistingDmgOnWipe = false,
            preserveExistingMsiOnWipe = false
        )
    }
}

// Silicon → Intel chaining: Intel always runs after Silicon, sequentially, so staging dirs don't clash.
tasks.matching { it.name == "packageSiliconDmg" }.configureEach {
    if (isMacHost()) finalizedBy("packageIntelDmg")
}
tasks.matching { it.name == "packageIntelDmg" }.configureEach {
    mustRunAfter("packageSiliconDmg")
}




/**
 * Inject custom WiX (finish-dialog checkboxes) into jpackage's resource dir after it is cleared.
 */
private fun Project.configureWindowsInstallerResources() {
    if (!isWindowsHost()) return
    val overlayDir = layout.projectDirectory.dir("windows/jpackage-resources").asFile
    if (!overlayDir.isDirectory) return

    tasks.withType<AbstractJPackageTask>().configureEach {
        if (name != "packageMsi" && name != "packageReleaseMsi") return@configureEach
        val resourcesDir = layout.buildDirectory.dir("compose/tmp/resources").get().asFile
        doFirst {
            resourcesDir.mkdirs()
            overlayDir.copyRecursively(resourcesDir, overwrite = true)
            logger.lifecycle("Synchronously copied WiX overlay resources to ${resourcesDir.absolutePath}")
        }
    }
}

configureWindowsInstallerResources()

