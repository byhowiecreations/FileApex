// Desktop packages only: ship the Material icons the app draws and the native libraries (Iroh, SQLite, JNA, JLine, Skia)
// of the build host only, instead of the full 36 MB icon jar and the natives of every platform and architecture.
// Android is untouched (R8 shrinks icons there and the natives come from the Android artifacts).
import java.util.zip.ZipFile

val slimDir = layout.buildDirectory.dir("slim-jars")

val iconStyles = "Filled|Outlined|Rounded|Sharp|TwoTone"
val iconRefs = listOf(
    // Icons.Outlined.Folder, Icons.Default.Folder, Icons.AutoMirrored.Filled.ArrowBack
    Regex("""Icons\s*\.\s*(AutoMirrored\s*\.\s*)?($iconStyles|Default)\s*\.\s*([A-Z][A-Za-z0-9]*)"""),
    // import androidx.compose.material.icons.filled.Folder
    Regex("""androidx\.compose\.material\.icons\.(automirrored\.)?(${iconStyles.lowercase()})\.([A-Z][A-Za-z0-9]*)"""),
)

val scanUsedIcons = tasks.register("scanUsedIcons") {
    val sources = files(
        fileTree(rootProject.file("shared/src")) { include("**/*.kt") },
        fileTree(project.file("src")) { include("**/*.kt") },
    )
    val output = slimDir.map { it.file("used-icons.txt") }
    inputs.files(sources)
    outputs.file(output)
    doLast {
        val used = sortedSetOf<String>()
        sources.files.forEach { file ->
            val text = file.readText()
            for (regex in iconRefs) {
                regex.findAll(text).forEach { match ->
                    val autoMirrored = match.groupValues[1].isNotBlank()
                    val style = match.groupValues[2].lowercase().let { if (it == "default") "filled" else it }
                    used += (if (autoMirrored) "automirrored/" else "") + "$style/${match.groupValues[3]}"
                }
            }
        }
        output.get().asFile.apply { parentFile.mkdirs() }.writeText(used.joinToString("\n") + "\n")
        logger.lifecycle("Desktop icons used: ${used.size}")
    }
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun resolveJar(notation: String) = configurations.detachedConfiguration(dependencies.create(notation)).apply {
    isTransitive = false
}

// The version `compose.materialIconsExtended` pins; the build log lists the jar if it ever changes.
val composeVersion = "1.7.3"
val iconsJar = resolveJar("org.jetbrains.compose.material:material-icons-extended-desktop:$composeVersion")
val coreIconsJar = resolveJar("org.jetbrains.compose.material:material-icons-core-desktop:$composeVersion")
val irohJar = resolveJar(catalog.findLibrary("iroh").get().get().toString())

val slimIcons = tasks.register<Jar>("slimMaterialIconsJar") {
    dependsOn(scanUsedIcons)
    archiveFileName.set("material-icons-extended-desktop-$composeVersion-used.jar")
    destinationDirectory.set(slimDir)
    inputs.file(slimDir.map { it.file("used-icons.txt") })
    from(provider { iconsJar.files.map { zipTree(it) } })
    val usedIcons by lazy {
        slimDir.get().file("used-icons.txt").asFile.readLines().filter { it.isNotBlank() }.toSet()
    }
    exclude { element ->
        val path = element.relativePath.pathString
        val match = Regex("""androidx/compose/material/icons/((?:automirrored/)?[a-z]+)/([A-Za-z0-9]+)Kt(\$.*)?\.class""").matchEntire(path)
        match != null && "${match.groupValues[1]}/${match.groupValues[2]}" !in usedIcons
    }
}

val hostOs = System.getProperty("os.name").lowercase()
val hostArch = System.getProperty("os.arch").lowercase()
val irohNatives = when {
    "mac" in hostOs || "darwin" in hostOs -> listOf("darwin-")
    "win" in hostOs -> listOf("win32-x86-64/")
    hostArch == "aarch64" || hostArch == "arm64" -> listOf("linux-aarch64/")
    else -> listOf("linux-x86-64/")
}

val slimIroh = tasks.register<Jar>("slimIrohJar") {
    archiveFileName.set("iroh-${catalog.findVersion("iroh").get()}-${hostOs.take(3)}.jar")
    destinationDirectory.set(slimDir)
    from(provider { irohJar.files.map { zipTree(it) } })
    val nativePrefixes = listOf("darwin-", "win32-", "linux-")
    exclude { element ->
        val path = element.relativePath.pathString
        nativePrefixes.any { path.startsWith(it) } && irohNatives.none { path.startsWith(it) }
    }
}

val sqliteJar = resolveJar("androidx.sqlite:sqlite-bundled-jvm:${catalog.findVersion("sqlite").get()}")
// androidx.sqlite 2.7.0 and 2.7.1 ship no natives/osx_x64, so an Intel Mac could not open the database ("Cannot find a suitable
// SQLite binary for mac os x | x86_64"). 2.6.2 is the last with it: same 22 native methods, same SQLite 3.50.1 inside.
val sqliteIntelMacJar = resolveJar("androidx.sqlite:sqlite-bundled-jvm:2.6.2")
val isIntelMac = ("mac" in hostOs || "darwin" in hostOs) && (hostArch == "x86_64" || hostArch == "amd64")
val sqliteNatives = when {
    isIntelMac -> listOf("natives/osx_x64/")
    "mac" in hostOs || "darwin" in hostOs -> listOf("natives/osx_arm64/")
    "win" in hostOs -> listOf("natives/windows_")
    hostArch == "aarch64" || hostArch == "arm64" -> listOf("natives/linux_arm64/")
    else -> listOf("natives/linux_x64/")
}

val slimSqlite = tasks.register<Jar>("slimSqliteJar") {
    archiveFileName.set("sqlite-bundled-jvm-${catalog.findVersion("sqlite").get()}-${hostOs.take(3)}.jar")
    destinationDirectory.set(slimDir)
    from(provider { sqliteJar.files.map { zipTree(it) } }) {
        exclude { element ->
            val path = element.relativePath.pathString
            path.startsWith("natives/") && sqliteNatives.none { path.startsWith(it) }
        }
    }
    if (isIntelMac) {
        from(provider { sqliteIntelMacJar.files.map { zipTree(it) } }) { include("natives/osx_x64/**") }
    }
    doLast {
        val names = ZipFile(archiveFile.get().asFile).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        check(names.any { name -> !name.endsWith("/") && sqliteNatives.any { name.startsWith(it) } }) {
            "The SQLite jar has no SQLite native for this machine (${sqliteNatives.joinToString()}); the app could not open its database."
        }
    }
}

val isMacHost = "mac" in hostOs || "darwin" in hostOs
val isWindowsHost = "win" in hostOs
val isArmHost = hostArch == "aarch64" || hostArch == "arm64"

// JNA keeps one `com/sun/jna/<platform>/jnidispatch` library per platform next to its Java packages (win32, ptr, internal).
val jnaJar = resolveJar("net.java.dev.jna:jna:${catalog.findVersion("jna").get()}")
val jnaPlatform = when {
    isIntelMac -> "darwin-x86-64"
    isMacHost -> "darwin-aarch64"
    isWindowsHost -> "win32-x86-64"
    isArmHost -> "linux-aarch64"
    else -> "linux-x86-64"
}
val jnaNative = Regex("""com/sun/jna/([a-z0-9-]+)/(lib)?jnidispatch\.[a-z]+""")

val slimJna = tasks.register<Jar>("slimJnaJar") {
    archiveFileName.set("jna-${catalog.findVersion("jna").get()}-${hostOs.take(3)}.jar")
    destinationDirectory.set(slimDir)
    from(provider { jnaJar.files.map { zipTree(it) } })
    exclude { element -> jnaNative.matchEntire(element.relativePath.pathString)?.let { it.groupValues[1] != jnaPlatform } == true }
    doLast {
        val names = ZipFile(archiveFile.get().asFile).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        val natives = names.filter { jnaNative.matches(it) }
        check(natives.size == 1 && natives.single().startsWith("com/sun/jna/$jnaPlatform/")) {
            "The JNA jar has no native for this machine ($jnaPlatform); found ${natives}."
        }
    }
}

// JLine ships its natives as `org/jline/nativ/<Mac|Linux|Windows|FreeBSD>/<arch>/`, in the bundle jar and again in jline-native.
val jlineVersion = catalog.findVersion("jline").get()
val jlineJar = resolveJar("org.jline:jline:$jlineVersion")
val jlineNativeJar = resolveJar("org.jline:jline-native:$jlineVersion")
val jlineNative = Regex("""org/jline/nativ/([A-Za-z]+)/([a-z0-9_]+)/.+""")
val jlineHost = when {
    isMacHost -> "Mac" to (if (isIntelMac) "x86_64" else "arm64")
    isWindowsHost -> "Windows" to "x86_64"
    else -> "Linux" to (if (isArmHost) "arm64" else "x86_64")
}

fun configureSlimJline(task: Jar, jar: Configuration, fileName: String) = task.run {
    archiveFileName.set(fileName)
    destinationDirectory.set(slimDir)
    from(provider { jar.files.map { zipTree(it) } })
    exclude { element ->
        jlineNative.matchEntire(element.relativePath.pathString)?.let { it.groupValues[1] to it.groupValues[2] != jlineHost } == true
    }
    doLast {
        val names = ZipFile(archiveFile.get().asFile).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        val natives = names.filter { jlineNative.matches(it) }
        check(natives.isNotEmpty() && natives.all { jlineNative.matchEntire(it)!!.let { m -> m.groupValues[1] to m.groupValues[2] == jlineHost } }) {
            "The JLine jars have no native for this machine (${jlineHost.first}/${jlineHost.second}); found $natives."
        }
    }
}
val slimJline = tasks.register<Jar>("slimJlineJar") {
    configureSlimJline(this, jlineJar, "jline-$jlineVersion-${hostOs.take(3)}.jar")
}
val slimJlineNative = tasks.register<Jar>("slimJlineNativeJar") {
    configureSlimJline(this, jlineNativeJar, "jline-native-$jlineVersion-${hostOs.take(3)}.jar")
}

// The Skia runtime jar of every Mac carries the library of both Mac architectures; the Compose plugin unpacks the one it
// needs next to the jars and leaves the other inside the jar, where nothing loads it. Keep only the host's library.
val skikoRuntimeSuffix = when {
    isMacHost -> if (isIntelMac) "macos-x64" else "macos-arm64"
    isWindowsHost -> "windows-x64"
    isArmHost -> "linux-arm64"
    else -> "linux-x64"
}
val skikoLibrary = Regex("""(lib)?skiko-(macos|linux|windows)-[a-z0-9]+\.(dylib|so|dll)(\.sha256)?""")
val skikoVersion = provider {
    configurations.detachedConfiguration(dependencies.create("org.jetbrains.compose.ui:ui-desktop:${catalog.findVersion("compose-multiplatform").get()}"))
        .resolvedConfiguration.resolvedArtifacts.first { it.name == "skiko-awt" }.moduleVersion.id.version
}
val skikoRuntimeJar = provider {
    configurations.detachedConfiguration(dependencies.create("org.jetbrains.skiko:skiko-awt-runtime-$skikoRuntimeSuffix:${skikoVersion.get()}"))
        .apply { isTransitive = false }.files
}

val slimSkikoRuntime = tasks.register<Jar>("slimSkikoRuntimeJar") {
    archiveFileName.set(skikoVersion.map { "skiko-awt-runtime-$skikoRuntimeSuffix-$it.jar" })
    destinationDirectory.set(slimDir)
    from(skikoRuntimeJar.map { files -> files.map { zipTree(it) } })
    exclude { element ->
        val name = element.relativePath.pathString
        skikoLibrary.matchEntire(name) != null && !name.contains("skiko-$skikoRuntimeSuffix.")
    }
    doLast {
        val names = ZipFile(archiveFile.get().asFile).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        val libraries = names.filter { it.endsWith(".dylib") || it.endsWith(".so") || it.endsWith(".dll") }
        check(libraries.size == 1 && libraries.single().contains("skiko-$skikoRuntimeSuffix.")) {
            "The Skia runtime jar has no library for this machine ($skikoRuntimeSuffix); found $libraries."
        }
    }
}

configurations.matching { it.name == "desktopRuntimeClasspath" }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    exclude(group = "androidx.sqlite", module = "sqlite-bundled-jvm")
    exclude(group = "org.jetbrains.compose.material", module = "material-icons-extended-desktop")
    exclude(group = "org.jetbrains.compose.material", module = "material-icons-extended")
    exclude(group = "computer.iroh", module = "iroh")
    exclude(group = "net.java.dev.jna", module = "jna")
    exclude(group = "org.jline", module = "jline")
    exclude(group = "org.jline", module = "jline-native")
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-$skikoRuntimeSuffix")
}

// Fails the build when compiled code reaches an extended icon the slim jar left out, for example through
// a spelling the source scan above does not know. Runs before every desktop package is assembled.
val verifySlimIcons = tasks.register("verifySlimIcons") {
    dependsOn(slimIcons)
    dependsOn(":shared:compileKotlinDesktop", "compileKotlinDesktop")
    val classDirs = files(
        rootProject.file("shared/build/classes/kotlin/desktop/main"),
        project.file("build/classes/kotlin/desktop/main"),
    )
    inputs.files(classDirs)
    inputs.files(slimIcons)
    doLast {
        val reference = Regex("androidx/compose/material/icons/((?:automirrored/)?[a-z]+)/([A-Za-z0-9]+)Kt")
        val fullNames = ZipFile(iconsJar.singleFile).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        val slimNames = ZipFile(slimIcons.get().archiveFile.get().asFile).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        val coreNames = ZipFile(coreIconsJar.singleFile).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        val anyIconClass = Regex("androidx/compose/material/icons/(?:[A-Za-z0-9]+/)*[A-Z][A-Za-z0-9$]*")
        val missing = sortedSetOf<String>()
        var checked = 0
        classDirs.asFileTree.matching { include("**/*.class") }.forEach { file ->
            val text = String(file.readBytes(), Charsets.ISO_8859_1)
            anyIconClass.findAll(text).forEach { match ->
                val entry = match.value + ".class"
                if (entry in coreNames || entry in slimNames) checked++
                else if (entry !in fullNames && !match.value.endsWith("Kt")) missing += entry
            }
            reference.findAll(text).forEach { match ->
                val entry = "androidx/compose/material/icons/${match.groupValues[1]}/${match.groupValues[2]}Kt.class"
                if (entry in fullNames && entry !in slimNames) missing += entry
            }
        }
        check(missing.isEmpty()) {
            "Compiled code uses icons the slim icon jar dropped: ${missing.joinToString()}. " +
                "Reference them as Icons.<Style>.<Name> so the scan in desktop-slim-jars.gradle.kts sees them."
        }
        logger.lifecycle("Slim icons verified: $checked icon class references found in the compiled desktop classes")
    }
}

tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    dependsOn(verifySlimIcons)
}
