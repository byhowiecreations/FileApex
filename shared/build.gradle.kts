import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.io.ByteArrayOutputStream

@Suppress("UNCHECKED_CAST")
fun org.gradle.api.Project.readGoogleServicesRoot(): Map<String, Any>? {
    val file = rootProject.layout.projectDirectory.file("json/google-services.json").asFile
    if (!file.isFile) return null
    return JsonSlurper().parse(file) as Map<String, Any>
}

@Suppress("UNCHECKED_CAST")
fun org.gradle.api.Project.googleServicesOAuthClients(): List<Map<String, Any>> {
    val root = readGoogleServicesRoot() ?: return emptyList()
    val client = (root["client"] as? List<Map<String, Any>>)?.firstOrNull() ?: return emptyList()
    return client["oauth_client"] as? List<Map<String, Any>> ?: emptyList()
}

fun org.gradle.api.Project.webClientIdFromGoogleServices(): String =
    googleServicesOAuthClients()
        .firstOrNull { (it["client_type"] as? Number)?.toInt() == 3 }
        ?.get("client_id")
        ?.toString()
        .orEmpty()

@Suppress("UNCHECKED_CAST")
fun org.gradle.api.Project.firebaseProjectIdFromGoogleServices(): String {
    val root = readGoogleServicesRoot() ?: return ""
    val projectInfo = root["project_info"] as? Map<String, Any> ?: return ""
    return projectInfo["project_id"]?.toString().orEmpty()
}

@Suppress("UNCHECKED_CAST")
fun org.gradle.api.Project.firebaseApiKeyFromGoogleServices(): String {
    val root = readGoogleServicesRoot() ?: return ""
    val client = (root["client"] as? List<Map<String, Any>>)?.firstOrNull() ?: return ""
    val apiKeys = client["api_key"] as? List<Map<String, Any>> ?: return ""
    return apiKeys.firstOrNull()?.get("current_key")?.toString().orEmpty()
}

fun org.gradle.api.Project.resolvedFirebaseProjectId(): String =
    firebaseProjectIdFromGoogleServices()
        .ifBlank { providers.gradleProperty("fileapex.firebase.project.id").orElse("").get().trim() }

fun org.gradle.api.Project.resolvedFirebaseApiKey(): String =
    providers.gradleProperty("fileapex.firebase.desktop.api.key").orElse("").get().trim()
        .ifBlank { providers.environmentVariable("FIREBASE_DESKTOP_API_KEY").orElse("").get().trim() }
        .ifBlank { firebaseApiKeyFromGoogleServices() }
        .ifBlank { providers.gradleProperty("fileapex.firebase.api.key").orElse("").get().trim() }

fun org.gradle.api.Project.hasAndroidOAuthClientInGoogleServices(): Boolean =
    googleServicesOAuthClients().any { (it["client_type"] as? Number)?.toInt() == 1 }

@Suppress("UNCHECKED_CAST")
fun org.gradle.api.Project.firebaseProjectNumberFromGoogleServices(): String {
    val root = readGoogleServicesRoot() ?: return ""
    val projectInfo = root["project_info"] as? Map<String, Any> ?: return ""
    return projectInfo["project_number"]?.toString().orEmpty()
}

val macOAuthRedirectUri = "http://127.0.0.1:8765/callback"

/** Web OAuth client_secret JSON in json/ whose client_id matches the Firebase project number. */
@Suppress("UNCHECKED_CAST")
fun org.gradle.api.Project.resolveWebOAuthFromJson(): Pair<String, String> {
    val expectedPrefix = firebaseProjectNumberFromGoogleServices()
    if (expectedPrefix.isBlank()) return "" to ""
    val jsonDir = rootProject.layout.projectDirectory.dir("json").asFile
    if (!jsonDir.isDirectory) return "" to ""
    val slurper = JsonSlurper()
    return jsonDir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.contains("client_secret", ignoreCase = true) }
        .sortedByDescending { it.lastModified() }
        .firstNotNullOfOrNull { file ->
            val parsed = runCatching { slurper.parse(file) as Map<String, Any> }.getOrNull() ?: return@firstNotNullOfOrNull null
            val web = parsed["web"] as? Map<String, Any> ?: return@firstNotNullOfOrNull null
            val clientId = web["client_id"]?.toString().orEmpty()
            if (!clientId.startsWith("$expectedPrefix-")) return@firstNotNullOfOrNull null
            clientId to web["client_secret"]?.toString().orEmpty()
        }
        ?: ("" to "")
}

/** Desktop OAuth JSON in json/ (PKCE loopback, no client_secret required for token exchange). */
@Suppress("UNCHECKED_CAST")
fun org.gradle.api.Project.resolveDesktopOAuthFromJson(): Pair<String, String> {
    val expectedProject = resolvedFirebaseProjectId()
    if (expectedProject.isBlank()) return "" to ""
    val jsonDir = rootProject.layout.projectDirectory.dir("json").asFile
    if (!jsonDir.isDirectory) return "" to ""
    val slurper = JsonSlurper()
    return jsonDir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
        .sortedByDescending { it.lastModified() }
        .firstNotNullOfOrNull { file ->
            val parsed = runCatching { slurper.parse(file) as Map<String, Any> }.getOrNull()
                ?: return@firstNotNullOfOrNull null
            val installed = parsed["installed"] as? Map<String, Any> ?: return@firstNotNullOfOrNull null
            val projectId = installed["project_id"]?.toString().orEmpty()
            if (projectId.isNotBlank() && !projectId.equals(expectedProject, ignoreCase = true)) {
                return@firstNotNullOfOrNull null
            }
            val clientId = installed["client_id"]?.toString().orEmpty()
            if (clientId.isBlank()) return@firstNotNullOfOrNull null
            clientId to installed["client_secret"]?.toString().orEmpty()
        }
        ?: ("" to "")
}

fun org.gradle.api.Project.resolvedDesktopClientId(): String =
    providers.gradleProperty("fileapex.google.desktop.client.id").orElse("").get().trim()
        .ifBlank { resolveDesktopOAuthFromJson().first }

fun org.gradle.api.Project.resolvedDesktopClientSecret(): String =
    providers.gradleProperty("fileapex.google.desktop.client.secret").orElse("").get().trim()
        .ifBlank { resolveDesktopOAuthFromJson().second }

fun org.gradle.api.Project.macOAuthConfigured(): Boolean =
    (resolvedDesktopClientId().isNotBlank()) ||
        (webOAuthJsonHasMacRedirect() && resolvedWebClientSecret().isNotBlank())

@Suppress("UNCHECKED_CAST")
fun org.gradle.api.Project.webOAuthJsonHasMacRedirect(): Boolean {
    val expectedPrefix = firebaseProjectNumberFromGoogleServices()
    if (expectedPrefix.isBlank()) return false
    val jsonDir = rootProject.layout.projectDirectory.dir("json").asFile
    if (!jsonDir.isDirectory) return false
    val slurper = JsonSlurper()
    return jsonDir.listFiles().orEmpty().any { file ->
        if (!file.isFile || !file.name.contains("client_secret", ignoreCase = true)) return@any false
        val parsed = runCatching { slurper.parse(file) as Map<String, Any> }.getOrNull() ?: return@any false
        val web = parsed["web"] as? Map<String, Any> ?: return@any false
        val clientId = web["client_id"]?.toString().orEmpty()
        if (!clientId.startsWith("$expectedPrefix-")) return@any false
        @Suppress("UNCHECKED_CAST")
        val redirects = web["redirect_uris"] as? List<String> ?: emptyList()
        redirects.any { it.equals(macOAuthRedirectUri, ignoreCase = true) }
    }
}

fun org.gradle.api.Project.resolvedWebClientId(): String =
    webClientIdFromGoogleServices()
        .ifBlank { providers.gradleProperty("fileapex.google.web.client.id").orElse("").get().trim() }
        .ifBlank { resolveWebOAuthFromJson().first }

fun org.gradle.api.Project.resolvedWebClientSecret(): String =
    providers.gradleProperty("fileapex.google.web.client.secret").orElse("").get().trim()
        .ifBlank { resolveWebOAuthFromJson().second }

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

// Loaded from committed version.md via settings.gradle.kts (beforeProject).
val fileapexVersionName = extra["fileapex.version.name"] as String
val fileapexVersionCode = extra["fileapex.version.code"] as String
val fileapexFdroid = extra["fileapex.fdroid"] as Boolean
val fileapexNoIroh = extra["fileapex.noiroh"] as Boolean
val fileapexIroh16k = extra["fileapex.iroh16k"] as Boolean

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(layout.buildDirectory.dir("generated/fileapexAppVersion/kotlin"))
        }

        val jvmCommon by creating {
            dependsOn(commonMain)
        }

        val androidMain by getting {
            dependsOn(jvmCommon)
            // Firebase, Credential Manager and Play Services actuals, or no-op stubs for F-Droid.
            kotlin.srcDir(if (fileapexFdroid) "src/androidFdroid/kotlin" else "src/androidGoogle/kotlin")
            // Iroh ships prebuilt native libraries, which F-Droid does not accept, so that build leaves it out.
            // The Play bundle leaves it out too until its arm64 library is 16 KB aligned (see settings.gradle.kts).
            if (!fileapexFdroid) kotlin.srcDir(if (fileapexNoIroh) "src/irohStub/kotlin" else "src/irohMain/kotlin")
        }

        jvmCommon.dependencies {
            implementation(libs.bouncycastle.provider)
            implementation(libs.bouncycastle.pkix)
        }

        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)

            implementation(libs.room3.runtime)
            implementation(libs.sqlite.bundled)

            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.server.status.pages)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.qrose)
            api(libs.haze)
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.browser)
            implementation(libs.androidx.sharetarget)
            implementation(libs.androidx.activity.compose)
            implementation(libs.zxing.android.embedded)
            implementation(libs.androidx.work.runtime)
            if (!fileapexFdroid) {
                if (!fileapexNoIroh && fileapexIroh16k) implementation("computer.iroh:iroh-android:1.1.0-16k") // fdroid:remove
                if (!fileapexNoIroh && !fileapexIroh16k) implementation(libs.iroh.android) // fdroid:remove
                // The iroh-android POM asks for jna 5.15.0, whose libjnidispatch.so crashes 16 KB devices (RELRO end).
                if (!fileapexNoIroh) implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar") // fdroid:remove
                implementation(project.dependencies.platform(libs.firebase.bom)) // fdroid:remove
                implementation(libs.firebase.auth) // fdroid:remove
                implementation(libs.firebase.firestore) // fdroid:remove
                implementation(libs.firebase.messaging) // fdroid:remove
                implementation(libs.kotlinx.coroutines.play.services) // fdroid:remove
                implementation(libs.play.services.auth) // fdroid:remove
                implementation(libs.androidx.credentials) // fdroid:remove
                implementation(libs.androidx.credentials.play.services) // fdroid:remove
                implementation(libs.google.id) // fdroid:remove
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.androidx.test.core)
                implementation(libs.robolectric)
            }
        }


        val desktopMain by getting {
            dependsOn(jvmCommon)
            kotlin.srcDir(layout.buildDirectory.dir("generated/desktopCloud/kotlin"))
            kotlin.srcDir("src/irohMain/kotlin")
            dependencies {
                implementation(libs.iroh)
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.jna)
                implementation(libs.jline)
                implementation(libs.jline.terminal.jna)
                implementation(libs.jmdns)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(libs.junit)
            }
        }
    }
}

android {
    namespace = "com.fileapex.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        val webClientId = if (fileapexFdroid) "" else resolvedWebClientId()
        val webClientSecret = if (fileapexFdroid) "" else resolvedWebClientSecret()
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${escapeKotlinString(webClientId)}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_SECRET", "\"${escapeKotlinString(webClientSecret)}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("test").assets.srcDir("src/androidMain/assets")
}

dependencies {
    add("kspAndroid", libs.room3.compiler)
    add("kspDesktop", libs.room3.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

val generateDesktopCloudConfig = tasks.register("generateDesktopCloudConfig") {
    val outDir = layout.buildDirectory.dir("generated/desktopCloud/kotlin")
    val jsonDir = rootProject.layout.projectDirectory.dir("json")
    inputs.dir(jsonDir).optional()
    inputs.file(rootProject.layout.projectDirectory.file("json/google-services.json")).optional()
    val desktopClientId = providers.gradleProperty("fileapex.google.desktop.client.id").orElse("")
    val desktopClientSecret = providers.gradleProperty("fileapex.google.desktop.client.secret").orElse("")
    inputs.property("desktopClientId", desktopClientId)
    inputs.property("desktopClientSecret", desktopClientSecret)
    outputs.dir(outDir)
    doLast {
        val webClientId = resolvedWebClientId()
        val webClientSecret = resolvedWebClientSecret()
        val desktopClientId = resolvedDesktopClientId()
        val desktopClientSecret = resolvedDesktopClientSecret()
        val firebaseApiKey = resolvedFirebaseApiKey()
        val firebaseProjectId = resolvedFirebaseProjectId()
        val dir = outDir.get().asFile.resolve("com/fileapex/cloud")
        dir.mkdirs()
        dir.resolve("GeneratedDesktopCloudConfig.kt").writeText(
            """
            |package com.fileapex.cloud
            |
            |/** Generated from json/google-services.json + gradle.properties, do not edit or commit. */
            |internal object GeneratedDesktopCloudConfig {
            |    const val WEB_CLIENT_ID = "${escapeKotlinString(webClientId)}"
            |    const val WEB_CLIENT_SECRET = "${escapeKotlinString(webClientSecret)}"
            |    const val DESKTOP_CLIENT_ID = "${escapeKotlinString(desktopClientId)}"
            |    const val DESKTOP_CLIENT_SECRET = "${escapeKotlinString(desktopClientSecret)}"
            |    const val FIREBASE_API_KEY = "${escapeKotlinString(firebaseApiKey)}"
            |    const val FIREBASE_PROJECT_ID = "${escapeKotlinString(firebaseProjectId)}"
            |}
            """.trimMargin()
        )
    }
}

fun escapeKotlinString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n")

/**
 * Test builds only: `-Pfileapex.notice.url=...` (or the same key in ~/.gradle/gradle.properties) points the app at a
 * test notice file. Tasks that ship refuse to run while it is set.
 */
val fileapexNoticeUrlOverride: String? = providers.gradleProperty("fileapex.notice.url").orNull?.trim()?.takeIf { it.isNotEmpty() }

gradle.taskGraph.whenReady {
    val shipping = setOf("bundlePlayRelease", "shipPlayRelease", "packageBothDmg", "copyAllBuilds", "copyCompleteBuilds")
    val shipTask = allTasks.firstOrNull { it.name in shipping }
    if (fileapexNoticeUrlOverride != null && shipTask != null) {
        throw GradleException("fileapex.notice.url is set, but ${shipTask.name} ships a build. Remove the property so the app reads the official notice file.")
    }
}

val generateFileApexAppVersion = tasks.register("generateFileApexAppVersion") {
    val outDir = layout.buildDirectory.dir("generated/fileapexAppVersion/kotlin")
    val versionFile = rootProject.file("version.md")
    val versionName = fileapexVersionName
    val versionCode = fileapexVersionCode
    val noticeUrl = fileapexNoticeUrlOverride.orEmpty()
    inputs.file(versionFile)
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    inputs.property("noticeUrl", noticeUrl)
    outputs.dir(outDir)
    doLast {
        val noticeDir = outDir.get().asFile.resolve("com/fileapex/notice")
        noticeDir.mkdirs()
        noticeDir.resolve("GeneratedNoticeConfig.kt").writeText(
            """
            |package com.fileapex.notice
            |
            |/** Generated, do not edit. A non-empty URL is a test build reading notices from another file. */
            |internal object GeneratedNoticeConfig {
            |    const val NOTICE_URL = "${escapeKotlinString(noticeUrl)}"
            |}
            """.trimMargin()
        )
        val dir = outDir.get().asFile.resolve("com/fileapex/update")
        dir.mkdirs()
        dir.resolve("GeneratedAppVersion.kt").writeText(
            """
            |package com.fileapex.update
            |
            |/**
            | * Generated from version.md, do not edit.
            | *   name= → [NAME]
            | *   code= → [CODE]
            | */
            |internal object GeneratedAppVersion {
            |    const val NAME = "$versionName"
            |    const val CODE = $versionCode
            |}
            """.trimMargin()
        )
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateFileApexAppVersion)
}

/** Posting a notice: see docs/notice-tools/README.md and docs/notice-tools/sign_notice.sh. The tool lives in the desktop test sources and ships in no build. */
tasks.register<JavaExec>("signNotice") {
    group = "notice"
    description = "Generates keys, signs notice/notice.json or verifies a signed file (pass --args)."
    val testCompilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    dependsOn(testCompilation.compileTaskProvider)
    classpath(testCompilation.output.allOutputs, testCompilation.runtimeDependencyFiles)
    mainClass.set("com.fileapex.notice.tool.NoticeSignerMainKt")
    workingDir = rootProject.projectDir
}

tasks.matching { it.name.startsWith("ksp") }.configureEach {
    dependsOn(generateFileApexAppVersion)
}

kotlin.targets.matching { it.name == "desktop" }.configureEach {
    compilations.configureEach {
        compileTaskProvider.configure {
            dependsOn(generateDesktopCloudConfig)
        }
    }
}

tasks.matching { it.name.startsWith("ksp") && it.name.contains("Desktop", ignoreCase = true) }.configureEach {
    dependsOn(generateDesktopCloudConfig)
}

val themeGoldenDir = layout.projectDirectory.dir("src/desktopTest/resources/theme-goldens")
val recordThemeGoldens = providers.gradleProperty("recordThemeGoldens").getOrElse("false")

tasks.withType<Test>().matching { it.name == "desktopTest" }.configureEach {
    systemProperty("fileapex.themeGoldenDir", themeGoldenDir.asFile.absolutePath)
    systemProperty("fileapex.recordThemeGoldens", recordThemeGoldens)
    systemProperty("fileapex.themeDiffDir", layout.buildDirectory.dir("theme-golden-diffs").get().asFile.absolutePath)
    inputs.property("recordThemeGoldens", recordThemeGoldens)
    inputs.files(fileTree(themeGoldenDir)).withPropertyName("themeGoldens")
}

/**
 * Web OAuth client IDs are prefixed with the GCP project number. Firebase Auth rejects tokens
 * when [fileapex.google.web.client.id] belongs to a different project than [google-services.json].
 */
tasks.register("verifyGoogleOAuthProjectAlignment") {
    group = "verification"
    description = "Ensure Web OAuth client ID matches the Firebase/GCP project number"
    val googleServicesFile = rootProject.layout.projectDirectory.file("json/google-services.json")
    val webClientIdProperty = providers.gradleProperty("fileapex.google.web.client.id").orElse("")
    val firebaseAppIdProperty = providers.gradleProperty("fileapex.firebase.application.id").orElse("")
    inputs.file(googleServicesFile).optional()
    inputs.property("webClientId", webClientIdProperty)
    inputs.property("firebaseAppId", firebaseAppIdProperty)
    val marker = layout.buildDirectory.file("verification/google-oauth-project-aligned.ok")
    outputs.file(marker)
    doLast {
        val webClientId = resolvedWebClientId()
        require(webClientId.isNotBlank()) {
            "Missing Web OAuth client ID, add json/google-services.json or fileapex.google.web.client.id"
        }
        val webProjectNumber = webClientId.substringBefore('-')
        require(webProjectNumber.all { it.isDigit() }) {
            "Invalid fileapex.google.web.client.id format: $webClientId"
        }

        val firebaseAppId = providers.gradleProperty("fileapex.firebase.application.id").orElse("").get().trim()
        val firebaseProjectNumber = firebaseAppId.split(":").getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: run {
                val gs = rootProject.layout.projectDirectory.file("json/google-services.json").asFile
                if (!gs.isFile) {
                    error("Missing json/google-services.json and fileapex.firebase.application.id")
                }
                @Suppress("UNCHECKED_CAST")
                val parsed = JsonSlurper().parse(gs) as Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                (parsed["project_info"] as Map<String, Any>)["project_number"].toString()
            }

        check(webProjectNumber == firebaseProjectNumber) {
            "OAuth/Firebase project mismatch: fileapex.google.web.client.id uses GCP project " +
                "$webProjectNumber but Firebase is configured for project number " +
                "$firebaseProjectNumber. Use the Web client ID from Firebase Console → " +
                "Project settings → Your apps (fileapex-22813), or from oauth_client in " +
                "json/google-services.json."
        }
        logger.lifecycle(
            "Google OAuth aligned: Web client project_number=$webProjectNumber matches Firebase"
        )
        marker.get().asFile.parentFile.mkdirs()
        marker.get().asFile.writeText("ok")
    }
}

fun org.gradle.api.Project.releaseKeystoreSha1(): String {
    val keystoreFile = file("${System.getProperty("user.home")}/AndroidStudioProjects/signed_files/FileApex/fileapex-release.jks")
    if (!keystoreFile.isFile) return "(fileapex-release.jks not found)"
    val storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
        ?: return "(set KEYSTORE_PASSWORD to print release SHA-1)"
    val alias = providers.environmentVariable("KEY_ALIAS").orNull ?: "fileapex-alias"
    val output = ByteArrayOutputStream()
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
        isIgnoreExitValue = true
    }
    return Regex("""SHA1:\s*([0-9A-Fa-f:]+)""")
        .find(output.toString())
        ?.groupValues
        ?.get(1)
        ?.uppercase()
        ?: "(run :composeApp:printReleaseSha1 to inspect release SHA-1)"
}

tasks.register("verifyMacOAuthRedirect") {
    group = "verification"
    description = "Warn if Mac OAuth credentials are missing (Desktop JSON or Web client_secret JSON)"
    val jsonDir = rootProject.layout.projectDirectory.dir("json")
    val desktopClientIdProperty = providers.gradleProperty("fileapex.google.desktop.client.id").orElse("")
    val webClientIdProperty = providers.gradleProperty("fileapex.google.web.client.id").orElse("")
    val webClientSecretProperty = providers.gradleProperty("fileapex.google.web.client.secret").orElse("")
    inputs.dir(jsonDir).optional()
    inputs.property("desktopClientId", desktopClientIdProperty)
    inputs.property("webClientId", webClientIdProperty)
    inputs.property("webClientSecretPresent", webClientSecretProperty.map { it.isNotBlank() })
    val marker = layout.buildDirectory.file("verification/mac-oauth-redirect.ok")
    outputs.file(marker)
    doLast {
        if (macOAuthConfigured()) {
            if (resolvedDesktopClientId().isNotBlank()) {
                logger.lifecycle("Mac OAuth: Desktop client configured (PKCE loopback)")
            } else {
                logger.lifecycle("Mac OAuth redirect URI present in json/ Web client_secret")
            }
        } else {
            logger.warn(
                """
                |Mac Google Sign-In: OAuth credentials missing for desktop builds.
                |See docs/gcp-mac-oauth-setup.md, quickest fix:
                |  GCP project fileapex-22813 → Credentials → Create OAuth client → Desktop app
                |  → Download JSON → json/ → rebuild (no client_secret needed)
                |
                |Or Web application with redirect $macOAuthRedirectUri → Download JSON → json/
                """.trimMargin()
            )
        }
        marker.get().asFile.parentFile.mkdirs()
        marker.get().asFile.writeText(if (macOAuthConfigured()) "ok" else "warn")
    }
}

tasks.register("verifyFirebaseAndroidOAuthSetup") {
    group = "verification"
    description = "Ensure google-services.json includes an Android OAuth client (SHA-1 registered in Firebase)"
    val googleServicesFile = rootProject.layout.projectDirectory.file("json/google-services.json")
    inputs.file(googleServicesFile).optional()
    val marker = layout.buildDirectory.file("verification/firebase-android-oauth.ok")
    outputs.file(marker)
    doLast {
        if (!hasAndroidOAuthClientInGoogleServices()) {
            val releaseSha1 = releaseKeystoreSha1()
            val debugSha1 = "B9:81:86:C8:74:65:21:AF:20:E3:2C:E3:FF:BE:BB:55:08:22:42:60"
            error(
                """
                |google-services.json has no Android OAuth client (client_type 1).
                |Google Sign-In error 28444 means Firebase/GCP is missing your app signing SHA-1.
                |
                |Fix in Firebase Console → fileapex-22813 → Project settings → Your apps → Android com.fileapex:
                |  1. Add fingerprint SHA-1 (release): $releaseSha1
                |  2. Add fingerprint SHA-1 (debug, optional): $debugSha1
                |  3. Authentication → Sign-in method → enable Google
                |  4. Re-download google-services.json → json/google-services.json
                |  5. Rebuild and reinstall the APK
                |
                |After SHA-1 is added, the downloaded json must include an oauth_client with "client_type": 1.
                """.trimMargin()
            )
        }
        logger.lifecycle("Firebase Android OAuth client present in google-services.json")
        marker.get().asFile.parentFile.mkdirs()
        marker.get().asFile.writeText("ok")
    }
}

if (!fileapexFdroid) {
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn("verifyGoogleOAuthProjectAlignment", "verifyFirebaseAndroidOAuthSetup", "verifyMacOAuthRedirect")
    }
}

tasks.register("verifyDesktopSmokeTestConfig") {
    group = "verification"
    description = "Confirm desktop smoke-probe and cross-platform packaging tasks are wired"
    dependsOn(":composeApp:verifyDesktopPackagingTasks")
    doLast {
        check(tasks.findByName("runDesktopNetworkingSmoke") != null) {
            "Missing :shared:runDesktopNetworkingSmoke"
        }
        val smokeDoc = rootProject.layout.projectDirectory.file("docs/v0.4.1a-windows-smoke-test.md").asFile
        check(smokeDoc.isFile) {
            "Missing smoke test guide: ${smokeDoc.absolutePath}"
        }
        logger.lifecycle(
            "Desktop smoke test config OK - run :shared:runDesktopNetworkingSmoke on Mac or Windows LAN hosts."
        )
    }
}

tasks.register<JavaExec>("runDesktopNetworkingSmoke") {
    group = "verification"
    description = "Headless LAN/mDNS/share-server/database smoke probe (Mac or Windows host)"
    dependsOn("compileKotlinDesktop", "desktopProcessResources")
    classpath(
        kotlin.targets.getByName("desktop").compilations.getByName("main").runtimeDependencyFiles,
        kotlin.targets.getByName("desktop").compilations.getByName("main").output.allOutputs
    )
    mainClass.set("com.fileapex.platform.DesktopNetworkingSmokeProbe")
    standardInput = System.`in`
}

