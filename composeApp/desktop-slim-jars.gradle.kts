// Desktop packages only: ship the Material icons the app draws and the Iroh native library of the
// build host, instead of the full 36 MB icon jar and Iroh natives for every platform.
// Android is untouched (R8 shrinks icons there and the Iroh natives come from iroh-android).
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

configurations.matching { it.name == "desktopRuntimeClasspath" }.configureEach {
    exclude(group = "org.jetbrains.compose.material", module = "material-icons-extended-desktop")
    exclude(group = "org.jetbrains.compose.material", module = "material-icons-extended")
    exclude(group = "computer.iroh", module = "iroh")
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
