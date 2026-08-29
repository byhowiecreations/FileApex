plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinCompose) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room3) apply false
    alias(libs.plugins.googleServices) apply false
}

/** Git index paths that must stay 100755 so macOS/Linux can run ./gradlew and embed scripts. */
val gitExecutableScriptPaths = listOf(
    "gradlew",
    "scripts/build_complete.sh",
    "scripts/build_intel.sh",
    "scripts/build_silicon.sh",
    "scripts/ensure-git-executable-bits.sh",
    "macos/scripts/build_extensions.sh",
    "macos/scripts/build_tray_bridge.sh",
    "macos/scripts/embed_extensions.sh",
    "macos/scripts/register_extensions.sh",
    "macos/scripts/unique_main_uuid.sh",
)

tasks.register("verifyGitExecutableScripts") {
    group = "verification"
    description =
        "Fail if tracked gradlew/scripts are not 100755 in Git's index (common after Windows commits)"
    doLast {
        val git = ProcessBuilder("git", "rev-parse", "--git-dir")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        if (git.waitFor() != 0) {
            logger.lifecycle("Skipping verifyGitExecutableScripts — not a git checkout")
            return@doLast
        }
        val bad = mutableListOf<String>()
        for (path in gitExecutableScriptPaths) {
            val file = rootProject.file(path)
            if (!file.isFile) continue
            val ls = ProcessBuilder("git", "ls-files", "-s", "--", path)
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            val out = ls.inputStream.bufferedReader().readText().trim()
            if (ls.waitFor() != 0 || out.isEmpty()) continue
            val mode = out.split("\\s+".toRegex()).firstOrNull().orEmpty()
            if (mode != "100755") {
                bad += "$path (index mode $mode, expected 100755)"
            }
        }
        check(bad.isEmpty()) {
            buildString {
                appendLine("Git index missing executable bit on wrapper/scripts:")
                bad.forEach { appendLine("  • $it") }
                appendLine("Run: ./scripts/ensure-git-executable-bits.sh")
                appendLine("Windows commits: git add --chmod=+x <path> — see docs/git-cross-platform.md")
            }
        }
        logger.lifecycle("Git index executable bits OK for ${gitExecutableScriptPaths.size} paths")
    }
}
