import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinSerialization)
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
}

compose.desktop {
    application {
        mainClass = "com.omninode.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "OmniNode"
            // jpackage macOS requires MAJOR > 0; app name version stays omninode.version.name (e.g. 0.0.1c)
            packageVersion = "1.0.${providers.gradleProperty("omninode.version.code").get()}"

            macOS {
                bundleID = "com.omninode"
                // Prompt for Local Network so phones can reach this Mac share server.
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>OmniNode shares files with paired phones and computers on your local network.</string>
                        <key>NSBonjourServices</key>
                        <array>
                            <string>_http._tcp</string>
                        </array>
                    """.trimIndent()
                }
            }
        }
    }
}

/**
 * Builds Android + Mac packaging, then copies (never moves) APK, .app, and .dmg
 * into the project-root `current/` folder for quick manual testing.
 *
 * Uses `ditto` so .app bundles keep execute bits and resource forks intact
 * (Kotlin copyRecursively strips +x and breaks launching OmniNode.app).
 */
tasks.register("copyCurrentBuilds") {
    group = "distribution"
    description = "Assemble Android debug APK + Mac DMG/.app and copy them into root current/"
    dependsOn("assembleDebug", "packageDmg")

    doLast {
        val dest = rootProject.layout.projectDirectory.dir("current").asFile
        if (dest.exists()) {
            dest.deleteRecursively()
        }
        dest.mkdirs()

        fun dittoCopy(source: File) {
            check(source.exists()) { "Missing build output: ${source.absolutePath}" }
            val target = dest.resolve(source.name)
            val process = ProcessBuilder("ditto", source.absolutePath, target.absolutePath)
                .inheritIO()
                .start()
            val code = process.waitFor()
            check(code == 0) { "ditto failed ($code) for ${source.name}" }
            // Clear Gatekeeper quarantine so local test copies open without friction.
            ProcessBuilder("xattr", "-cr", target.absolutePath).start().waitFor()
            logger.lifecycle("Copied ${source.name} -> current/${source.name}")
        }

        val apkDir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        val apks = apkDir.listFiles().orEmpty().filter { it.isFile && it.extension == "apk" }
        check(apks.isNotEmpty()) { "No APK found in ${apkDir.absolutePath}" }
        apks.forEach(::dittoCopy)

        val dmgDir = layout.buildDirectory.dir("compose/binaries/main/dmg").get().asFile
        val dmgs = dmgDir.listFiles().orEmpty().filter { it.isFile && it.extension == "dmg" }
        check(dmgs.isNotEmpty()) { "No DMG found in ${dmgDir.absolutePath}" }
        dmgs.forEach(::dittoCopy)

        val appBundle = layout.buildDirectory.dir("compose/binaries/main/app/OmniNode.app").get().asFile
        dittoCopy(appBundle)

        val launchedBinary = dest.resolve("OmniNode.app/Contents/MacOS/OmniNode")
        check(launchedBinary.exists() && launchedBinary.canExecute()) {
            "OmniNode.app binary missing execute permission after copy"
        }
    }
}

