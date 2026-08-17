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
    firebaseApiKeyFromGoogleServices()
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

/** Desktop OAuth JSON in json/ (PKCE loopback — no client_secret required for token exchange). */
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
            kotlin.srcDir(layout.buildDirectory.dir("generated/fcmCredentials/kotlin"))
        }

        val jvmCommon by creating {
            dependsOn(commonMain)
        }

        val androidMain by getting {
            dependsOn(jvmCommon)
        }

        jvmCommon.dependencies {
            implementation(libs.bouncycastle.provider)
        }

        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material3.adaptive)
            implementation(libs.compose.material3.adaptive.layout)
            implementation(libs.compose.material3.adaptive.navigation)
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
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.sharetarget)
            implementation(libs.androidx.activity.compose)
            implementation(libs.zxing.android.embedded)
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.auth)
            implementation(libs.firebase.firestore)
            implementation(libs.firebase.messaging)
            implementation(libs.kotlinx.coroutines.play.services)
            implementation(libs.androidx.work.runtime)
            implementation(libs.play.services.auth)
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
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.jna)
                implementation(libs.jmdns)
            }
        }
    }
}

android {
    namespace = "com.fileapex.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        val webClientId = resolvedWebClientId()
        val webClientSecret = resolvedWebClientSecret()
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
            |/** Generated from json/google-services.json + gradle.properties — do not edit or commit. */
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

fun resolveFirebaseServiceAccountJson(rootDir: java.io.File, explicitPath: String): java.io.File? {
    if (explicitPath.isNotBlank()) {
        val configured = rootDir.resolve(explicitPath)
        if (configured.isFile) return configured
    }
    val jsonDir = rootDir.resolve("json")
    if (jsonDir.isDirectory) {
        jsonDir.listFiles()
            ?.firstOrNull { file -> file.isFile && file.name.contains("firebase-adminsdk") && file.name.endsWith(".json") }
            ?.let { return it }
    }
    return rootDir.listFiles()
        ?.firstOrNull { file -> file.isFile && file.name.contains("firebase-adminsdk") && file.name.endsWith(".json") }
}

val generateFcmCredentials = tasks.register("generateFcmCredentials") {
    val outDir = layout.buildDirectory.dir("generated/fcmCredentials/kotlin")
    val jsonPath = providers.gradleProperty("fileapex.firebase.service.account.json").orElse("")
    inputs.property("jsonPath", jsonPath)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.resolve("com/fileapex/cloud")
        dir.mkdirs()
        val jsonFile = resolveFirebaseServiceAccountJson(rootProject.projectDir, jsonPath.get())
        if (jsonFile == null) {
            dir.resolve("GeneratedFcmCredentials.kt").writeText(
                """
                |package com.fileapex.cloud
                |
                |/** Generated at build time — credentials empty when service account JSON is missing. */
                |internal object GeneratedFcmCredentials {
                |    const val PROJECT_ID = ""
                |    const val CLIENT_EMAIL = ""
                |    const val PRIVATE_KEY_PEM = ""
                |}
                |
                |internal fun GeneratedFcmCredentials.toConfig(): FcmServiceAccountConfig? = null
                """.trimMargin()
            )
            return@doLast
        }
        @Suppress("UNCHECKED_CAST")
        val json = JsonSlurper().parseText(jsonFile.readText()) as Map<String, Any?>
        val projectId = json["project_id"]?.toString().orEmpty()
        val clientEmail = json["client_email"]?.toString().orEmpty()
        val privateKey = json["private_key"]?.toString().orEmpty()
        dir.resolve("GeneratedFcmCredentials.kt").writeText(
            """
            |package com.fileapex.cloud
            |
            |/** Generated from local Firebase Admin SDK JSON — never commit this file. */
            |internal object GeneratedFcmCredentials {
            |    const val PROJECT_ID = "${escapeKotlinString(projectId)}"
            |    const val CLIENT_EMAIL = "${escapeKotlinString(clientEmail)}"
            |    const val PRIVATE_KEY_PEM = "${escapeKotlinString(privateKey)}"
            |}
            |
            |internal fun GeneratedFcmCredentials.toConfig(): FcmServiceAccountConfig? {
            |    if (PROJECT_ID.isBlank() || CLIENT_EMAIL.isBlank() || PRIVATE_KEY_PEM.isBlank()) return null
            |    return FcmServiceAccountConfig(
            |        projectId = PROJECT_ID,
            |        clientEmail = CLIENT_EMAIL,
            |        privateKeyPem = PRIVATE_KEY_PEM
            |    )
            |}
            """.trimMargin()
        )
    }
}

val generateFileApexAppVersion = tasks.register("generateFileApexAppVersion") {
    val outDir = layout.buildDirectory.dir("generated/fileapexAppVersion/kotlin")
    val versionFile = rootProject.file("version.md")
    val versionName = fileapexVersionName
    val versionCode = fileapexVersionCode
    inputs.file(versionFile)
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.resolve("com/fileapex/update")
        dir.mkdirs()
        dir.resolve("GeneratedAppVersion.kt").writeText(
            """
            |package com.fileapex.update
            |
            |/**
            | * Generated from version.md — do not edit.
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
    dependsOn(generateFileApexAppVersion, generateFcmCredentials)
}

tasks.matching { it.name.startsWith("ksp") }.configureEach {
    dependsOn(generateFileApexAppVersion, generateFcmCredentials)
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
            "Missing Web OAuth client ID — add json/google-services.json or fileapex.google.web.client.id"
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
                |See docs/gcp-mac-oauth-setup.md — quickest fix:
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

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("verifyGoogleOAuthProjectAlignment", "verifyFirebaseAndroidOAuthSetup", "verifyMacOAuthRedirect")
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

