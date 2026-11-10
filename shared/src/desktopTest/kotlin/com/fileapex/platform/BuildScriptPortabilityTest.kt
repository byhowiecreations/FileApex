package com.fileapex.platform

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * scripts/build-fileapex.sh must work for someone who is not the maintainer: settings in a per-user file that is data and
 * never code, no personal paths, remote builds generated from those settings, builds that need no secrets, and a graceful
 * skip for targets that are not set up. Everything runs against temp folders and fake ssh and scp programs.
 */
class BuildScriptPortabilityTest {
    private val scriptFile = File("../scripts/build-fileapex.sh")
    private val script = scriptFile.absolutePath

    private class Result(val code: Int, val out: String)

    private fun temp() = Files.createTempDirectory("fileapex-port").toFile().also { it.deleteOnExit() }

    private fun sh(command: String, env: Map<String, String> = emptyMap(), dir: File? = null): Result {
        val pb = ProcessBuilder("bash", "-c", command).redirectErrorStream(true)
        if (dir != null) pb.directory(dir)
        pb.environment().putAll(env)
        val process = pb.start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue("timed out: $command", process.waitFor(120, TimeUnit.SECONDS))
        return Result(process.exitValue(), out)
    }

    private class Box(val root: File, val project: File, val config: File, val home: File) {
        fun env(extra: Map<String, String> = emptyMap()) = mapOf(
            "FILEAPEX_DIR" to project.absolutePath,
            "FILEAPEX_CONFIG" to config.absolutePath,
            "HOME" to home.absolutePath,
            "CLI_MODE" to "1",
        ) + extra
    }

    private fun box(): Box {
        val root = temp()
        val project = File(root, "project").also { File(it, "scripts").mkdirs() }
        File(project, "version.md").writeText("name=1.0\ncode=1\n")
        File(project, "gradlew").also { it.writeText("#!/bin/bash\nexit 0\n"); it.setExecutable(true) }
        val home = File(root, "home").also { it.mkdirs() }
        return Box(root, project, File(root, "config/machines.env"), home)
    }

    private fun call(b: Box, command: String, extra: Map<String, String> = emptyMap()) =
        sh("FILEAPEX_SOURCE_ONLY=1 source '$script'; $command", b.env(extra), b.project)

    @Test fun settingsValuesAreDataAndNeverRunAsCode() {
        val b = box(); val marker = File(b.root, "marker")
        val r = call(b, "config_set WIN_HOST 'u@h;touch ${marker.absolutePath}'; config_get WIN_HOST")
        assertEquals(r.out, 0, r.code)
        assertTrue(r.out, r.out.contains("u@h;touch"))
        assertFalse("a value ran as a command", marker.exists())
        val again = call(b, "config_get WIN_HOST; config_get MISSING_KEY; echo done")
        assertTrue(again.out, again.out.endsWith("done\n"))
        assertFalse(marker.exists())
    }

    @Test fun settingsFileIsPrivateAndQuotesAreStripped() {
        val b = box()
        b.config.parentFile.mkdirs(); b.config.writeText("LIN_KEY=\"/home/x/my key\"\r\nWIN_DIR='C:/a b'\n")
        val r = call(b, "config_get LIN_KEY; echo; config_get WIN_DIR")
        assertEquals("/home/x/my key\nC:/a b", r.out.trim())
        call(b, "config_set REMOTE gitea")
        val perms = Files.getPosixFilePermissions(b.config.toPath()).map { it.name }.toSet()
        assertEquals(setOf("OWNER_READ", "OWNER_WRITE"), perms)
    }

    @Test fun settingsLiveOutsideTheRepoByDefault() {
        val b = box()
        val r = sh("FILEAPEX_SOURCE_ONLY=1 source '$script'; echo \"\$CONFIG_FILE\"", mapOf("FILEAPEX_DIR" to b.project.absolutePath, "HOME" to b.home.absolutePath, "XDG_CONFIG_HOME" to ""))
        assertEquals(File(b.home, ".config/fileapex/machines.env").absolutePath, r.out.trim())
        val win = sh("FILEAPEX_SOURCE_ONLY=1 FILEAPEX_UNAME=MINGW64_NT-10.0 source '$script'; echo \"\$CONFIG_FILE\"",
            mapOf("FILEAPEX_DIR" to b.project.absolutePath, "HOME" to b.home.absolutePath, "APPDATA" to "/c/Users/x/AppData/Roaming"))
        assertEquals("/c/Users/x/AppData/Roaming/FileApex/machines.env", win.out.trim())
    }

    @Test fun theOperatingSystemIsDetectedFromUname() {
        val b = box()
        mapOf("Darwin" to "mac", "Linux" to "linux", "MINGW64_NT-10.0-26100" to "windows", "MSYS_NT-10.0" to "windows").forEach { (uname, os) ->
            assertEquals(uname, os, call(b, "host_os", mapOf("FILEAPEX_UNAME" to uname)).out.trim())
        }
    }

    @Test fun theOldHostsFileMovesOverOnceAndIsLeftInPlace() {
        val b = box()
        val legacy = File(b.project, "scripts/hosts.local.env")
        legacy.writeText("WIN_HOST=u@win\nWIN_DIR=\"C:/Users/u/FileApex\"\nLIN_HOST=u@lin\nLIN_KEY=/k/id\n")
        val first = call(b, "migrate_legacy_hosts; config_get WIN_HOST; echo; config_get WIN_DIR; echo; config_get WIN_MODE; echo; config_get LIN_MODE; echo; config_get LIN_KEY")
        assertEquals(first.out, "u@win\nC:/Users/u/FileApex\nssh\nssh\n/k/id", first.out.lines().filterNot { it.contains("Moved your build hosts") }.joinToString("\n").trim())
        assertTrue(first.out, first.out.contains("hosts.local.env"))
        assertTrue(legacy.isFile)
        b.config.writeText("WIN_HOST=other\n")
        val second = call(b, "migrate_legacy_hosts; config_get WIN_HOST")
        assertEquals("other", second.out.trim())
    }

    @Test fun onlyPlainValuesAreAcceptedForAnotherMachine() {
        val b = box()
        listOf("u@h;touch x", "a b", "a\"b", "a'b", "a\$b", "a`b", "-oProxyCommand=x", "C:\\dir\\", "a|b", "a&b", "a(b)", "").forEach {
            assertTrue("accepted: $it", call(b, "check_remote_value X '${it.replace("'", "'\\''")}'").code != 0)
        }
        listOf("u@host.example", "C:/Users/me/FileApex", "C:\\msys64\\ucrt64\\bin", "~/toolchain/go", "FileApex", "/home/u/.ssh/id_ed25519").forEach {
            assertEquals("rejected: $it", 0, call(b, "check_remote_value X '$it'").code)
        }
        assertTrue(call(b, "check_ref_value Branch 'wip/0.20.7-x'").code == 0)
        assertTrue(call(b, "check_ref_value Branch 'a;b'").code != 0)
    }

    @Test fun noPersonalPathsRemainInTheScript() {
        val text = scriptFile.readText()
        listOf("cliff", "/toolchain/", "AndroidStudioProjects", "172.16", "1.27.1").forEach {
            assertFalse("personal text '$it' is back in the script", text.contains(it))
        }
    }

    @Test fun theProjectFolderDefaultsToTheScriptsOwnCheckout() {
        val b = box()
        val r = sh("env -u FILEAPEX_DIR bash -c 'FILEAPEX_SOURCE_ONLY=1 source \"$script\"; echo \"\$PROJECT_DIR\"'", mapOf("HOME" to b.home.absolutePath))
        assertEquals(scriptFile.absoluteFile.parentFile.parentFile.canonicalPath, File(r.out.trim()).canonicalPath)
    }

    @Test fun theSigningFolderComesFromTheKeychainPathInTheEnvFileThenTheSettings() {
        val b = box()
        val envFile = File(b.root, "signing.env"); val keys = File(b.root, "keys").also { it.mkdirs() }
        envFile.writeText("export KEYSTORE_PASSWORD=sekret-value-1\nexport FILEAPEX_MAC_SIGN_KEYCHAIN=\"${keys.absolutePath}/fileapex-signing.keychain-db\"\n")
        val fromEnv = call(b, "echo \"\$SIGNING_DIR\"", mapOf("FILEAPEX_ENV_FILE" to envFile.absolutePath))
        assertEquals(keys.absolutePath, fromEnv.out.trim())
        assertFalse(fromEnv.out.contains("sekret"))
        b.config.parentFile.mkdirs(); b.config.writeText("SIGNING_DIR=/set/by/user\n")
        assertEquals("/set/by/user", call(b, "echo \"\$SIGNING_DIR\"", mapOf("FILEAPEX_ENV_FILE" to envFile.absolutePath)).out.trim())
        b.config.delete()
        val none = call(b, "echo \"\$SIGNING_DIR\"", mapOf("FILEAPEX_ENV_FILE" to File(b.root, "absent").absolutePath))
        assertEquals(File(b.home, ".fileapex/signing").absolutePath, none.out.trim())
    }

    @Test fun releaseSigningIsOnlyRequiredForReleaseBuilds() {
        val b = box()
        fun needs(tasks: String, extra: Map<String, String> = emptyMap()) = call(b, "build_needs_signing $tasks", extra).code == 0
        assertTrue(needs(":composeApp:shipGithubRelease"))
        assertTrue(needs(":composeApp:bundlePlayRelease"))
        assertTrue(needs(":composeApp:packageSiliconApp"))
        assertFalse(needs(":composeApp:assembleGithubDebug"))
        assertFalse(needs(":composeApp:packageSiliconApp", mapOf("FILEAPEX_MAC_UNSIGNED" to "1")))
        assertTrue(needs(":composeApp:shipGithubRelease :composeApp:packageSiliconApp", mapOf("FILEAPEX_MAC_UNSIGNED" to "1")))
    }

    @Test fun aReleaseBuildWithoutSecretsExplainsWhatStillWorks() {
        val b = box()
        val r = call(b, "NEEDS_SIGNING=1; run_with_live_view Label Sub true")
        assertTrue(r.out, r.code != 0)
        assertTrue(r.out, r.out.contains("signing.local.env"))
        assertTrue(r.out, r.out.contains("-github-debug") && r.out.contains("-doctor"))
    }

    @Test fun aDebugApkIsRescuedWithADebugName() {
        val b = box()
        val dir = File(b.project, "composeApp/build/outputs/apk/github/debug").also { it.mkdirs() }
        File(dir, "composeApp-github-arm64-v8a-debug.apk").writeText("a")
        File(dir, "composeApp-github-armeabi-v7a-debug.apk").writeText("b")
        val r = call(b, "salvage_leftovers")
        assertEquals(r.out, 0, r.code)
        assertTrue(File(b.project, "current/FileApex-v1.0-debug.apk").isFile)
        assertTrue(File(b.project, "current/FileApex-v1.0-debug-armeabi-v7a.apk").isFile)
    }

    @Test fun macBuildsAreRefusedOnOtherSystems() {
        val b = box(); b.config.parentFile.mkdirs(); b.config.writeText("REMOTE=origin\n")
        val r = call(b, "execute_build Mac :composeApp:packageSiliconApp", mapOf("FILEAPEX_UNAME" to "Linux"))
        assertTrue(r.out, r.code != 0)
        assertTrue(r.out, r.out.contains("Mac builds need a Mac"))
    }

    @Test fun aBuildWithoutASettingsFileSaysToInitialize() {
        val b = box()
        val r = call(b, "execute_build Mac :composeApp:packageSiliconApp")
        assertTrue(r.out, r.code != 0)
        assertTrue(r.out, r.out.contains("-initialize"))
    }

    @Test fun completeBuildSkipsEverythingThatIsNotSetUpAndSaysSo() {
        val b = box(); b.config.parentFile.mkdirs(); b.config.writeText("REMOTE=origin\n")
        val r = call(b, "run_complete_all_platforms 1.0 main", mapOf("FILEAPEX_UNAME" to "Linux"))
        assertEquals(r.out, 0, r.code)
        assertTrue(r.out, r.out.contains("need a Mac"))
        assertTrue(r.out, r.out.contains("Not built (not configured): Windows Linux"))
    }

    @Test fun anUnconfiguredTargetReturnsTheSkipCodeAndLocalNeedsTheRightSystem() {
        val b = box(); b.config.parentFile.mkdirs(); b.config.writeText("WIN_MODE=off\nLIN_MODE=local\n")
        assertEquals(64, call(b, "run_windows_target 1.0 main").code)
        val wrongOs = call(b, "run_linux_target 1.0 main", mapOf("FILEAPEX_UNAME" to "Darwin"))
        assertTrue(wrongOs.out, wrongOs.code == 1 && wrongOs.out.contains("needs a Linux machine"))
        b.config.writeText("WIN_MODE=local\n")
        val winWrong = call(b, "run_windows_target 1.0 main", mapOf("FILEAPEX_UNAME" to "Darwin"))
        assertTrue(winWrong.out, winWrong.code == 1 && winWrong.out.contains("needs a Windows machine"))
    }

    private fun git(dir: File, vararg args: String) {
        val r = sh("git -c user.email=t@t -c user.name=t -c commit.gpgsign=false ${args.joinToString(" ")}", dir = dir)
        assertEquals(r.out, 0, r.code)
    }

    @Test fun thePushCheckUsesTheConfiguredRemoteNotAGiteaName() {
        val b = box()
        val bare = File(b.root, "remote.git"); sh("git init -q --bare '${bare.absolutePath}'")
        git(b.project, "init", "-q", "-b", "work")
        File(b.project, "a.txt").writeText("a")
        git(b.project, "add", "a.txt", "version.md"); git(b.project, "commit", "-qm", "one")
        git(b.project, "remote", "add", "mirror", bare.absolutePath)
        b.config.parentFile.mkdirs(); b.config.writeText("REMOTE=mirror\n")
        val unpushed = call(b, "verify_git_pushed_for_remote work")
        assertTrue(unpushed.out, unpushed.code != 0)
        assertTrue(unpushed.out, unpushed.out.contains("mirror/work") && !unpushed.out.contains("Gitea"))
        git(b.project, "push", "-q", "mirror", "work")
        assertEquals(call(b, "verify_git_pushed_for_remote work").out, 0, call(b, "verify_git_pushed_for_remote work").code)
    }

    /** Fake ssh and scp that record every call; scp "downloads" a Windows installer or Linux packages. */
    private fun fakeBin(b: Box): File {
        val bin = File(b.root, "bin").also { it.mkdirs() }
        val log = File(b.root, "calls.log")
        File(bin, "ssh").apply {
            writeText("""#!/bin/bash
echo "ssh $*" >> '${log.absolutePath}'
case "${'$'}*" in
  *"echo ok"*) echo ok ;;
  *rev-parse*) echo true ;;
  *Test-Path*|*Get-Command*|*"echo True"*) [ -n "${'$'}FAKE_SSH_FALSE" ] && echo False || echo True ;;
  *Get-FileHash*) printf x | shasum -a 256 | cut -d' ' -f1 ;;
  *sha256sum*|*"shasum -a 256"*) printf "%s  f\n" "${'$'}(printf x | shasum -a 256 | cut -d' ' -f1)" ;;
esac
exit 0
""")
            setExecutable(true)
        }
        File(bin, "scp").apply {
            writeText("""#!/bin/bash
echo "scp $*" >> '${log.absolutePath}'
dest="${'$'}{@: -1}"
case "${'$'}*" in
  *.exe*) printf x > "${'$'}dest/FileApex-v1.0.exe" ;;
  *FileApex-v1.0-\**) for n in amd64.deb x86_64.rpm x86_64.AppImage; do printf x > "${'$'}dest/FileApex-v1.0-${'$'}n"; done ;;
  *current/FileApex-v1.0*) src="${'$'}{@: -2:1}"; printf x > "${'$'}dest/${'$'}(basename "${'$'}{src#*:}")" ;;
esac
exit 0
""")
            setExecutable(true)
        }
        return bin
    }

    private fun repoWithHead(b: Box): String {
        git(b.project, "init", "-q", "-b", "work")
        File(b.project, "a.txt").writeText("a")
        git(b.project, "add", "a.txt", "version.md"); git(b.project, "commit", "-qm", "one")
        return sh("git rev-parse HEAD", dir = b.project).out.trim()
    }

    @Test fun theWindowsBuildIsGeneratedFromTheSettings() {
        val b = box(); val head = repoWithHead(b); val bin = fakeBin(b)
        b.config.parentFile.mkdirs()
        b.config.writeText("WIN_MODE=ssh\nWIN_HOST=dev@winbox\nWIN_DIR=D:/src/FileApex\nWIN_MSYS=D:\\tools\\ucrt64\\bin\nWIN_KEY=/k/id\nREMOTE=mirror\n")
        val r = call(b, "run_remote_windows 1.0 work", mapOf("PATH" to "${bin.absolutePath}:${System.getenv("PATH")}"))
        assertEquals(r.out, 0, r.code)
        val calls = File(b.root, "calls.log").readText()
        assertTrue(calls, calls.contains("-i /k/id") && calls.contains("dev@winbox"))
        assertTrue(calls, calls.contains("-Branch \"work\"") && calls.contains("-Expected \"$head\""))
        assertTrue(calls, calls.contains("-Remote \"mirror\"") && calls.contains("-Dir \"D:/src/FileApex\"") && calls.contains("-Msys \"D:\\tools\\ucrt64\\bin\""))
        assertTrue(calls, calls.contains("dev@winbox:D:/src/FileApex/current/FileApex-v1.0.exe"))
        assertTrue(File(b.project, "current/FileApex-v1.0.exe").isFile)
    }

    @Test fun theLinuxBuildIsGeneratedFromTheSettingsAndVerified() {
        val b = box(); val head = repoWithHead(b); val bin = fakeBin(b)
        b.config.parentFile.mkdirs()
        b.config.writeText("LIN_MODE=ssh\nLIN_HOST=dev@linbox\nLIN_DIR=~/src/FileApex\nLIN_GO=~/sdk/go\nLIN_JDK=~/sdk/jdk\nREMOTE=mirror\n")
        val r = call(b, "run_remote_linux 1.0 work", mapOf("PATH" to "${bin.absolutePath}:${System.getenv("PATH")}"))
        assertEquals(r.out, 0, r.code)
        val calls = File(b.root, "calls.log").readText()
        assertTrue(calls, calls.contains("bash build_full_linux.sh 'work' '$head' 'mirror' '~/src/FileApex' '~/sdk/go' '~/sdk/jdk'"))
        assertTrue(calls, calls.contains("dev@linbox:src/FileApex/current/FileApex-v1.0-*"))
        assertTrue(File(b.project, "current/FileApex-v1.0-amd64.deb").isFile)
    }

    @Test fun anUnsafeHostIsRefusedBeforeAnythingIsSent() {
        val b = box(); repoWithHead(b); val bin = fakeBin(b)
        b.config.parentFile.mkdirs()
        b.config.writeText("WIN_MODE=ssh\nWIN_HOST=dev@winbox\nWIN_DIR=\"C:/a;b\"\n")
        val r = call(b, "run_remote_windows 1.0 work", mapOf("PATH" to "${bin.absolutePath}:${System.getenv("PATH")}"))
        assertTrue(r.out, r.code != 0)
        assertFalse(File(b.root, "calls.log").exists())
    }

    @Test fun theGeneratedScriptsKeepTheCheckoutSafetyChecks() {
        val text = scriptFile.readText()
        val windows = text.substringAfter("build_full.ps1\" <<'EOF'").substringBefore("\nEOF")
        val linux = text.substringAfter("build_full_linux.sh\" <<'EOF'").substringBefore("\nEOF")
        assertTrue(windows.contains("git fetch ${'$'}Remote") && windows.contains("rev-parse HEAD") && windows.contains("Remove-Item"))
        assertTrue(linux.contains("git fetch \"${'$'}REMOTE\"") && linux.contains("rev-parse HEAD") && linux.contains("git status --porcelain"))
    }

    @Test fun setupCommandsAreDocumentedInTheHelp() {
        val b = box()
        val help = sh("bash '$script' -h", b.env())
        listOf("-initialize", "-machines", "-github-debug", "-doctor").forEach { assertTrue("$it missing:\n${help.out}", help.out.contains(it)) }
    }

    @Test fun machinesShowPrintsTheFileOrPointsAtInitialize() {
        val b = box()
        val none = call(b, "machines --show")
        assertTrue(none.out, none.out.contains("-initialize"))
        b.config.parentFile.mkdirs(); b.config.writeText("WIN_MODE=off\n")
        assertTrue(call(b, "machines --show").out.contains("WIN_MODE=off"))
    }

    @Test fun doctorChecksOnlyWhatTheRunningSystemNeeds() {
        val b = box()
        val linux = call(b, "doctor", mapOf("FILEAPEX_UNAME" to "Linux")).out
        assertTrue(linux, linux.contains("appimagetool") && !linux.contains("Rosetta") && !linux.contains("Signing identity"))
        val windows = call(b, "doctor", mapOf("FILEAPEX_UNAME" to "MINGW64_NT-10.0")).out
        assertTrue(windows, windows.contains("Inno Setup") && windows.contains("MSYS2") && !windows.contains("Rosetta"))
    }

    @Test fun doctorChecksAConfiguredSshTargetReadOnly() {
        val b = box(); val bin = fakeBin(b)
        b.config.parentFile.mkdirs()
        b.config.writeText("LIN_MODE=ssh\nLIN_HOST=dev@linbox\nLIN_DIR=FileApex\n")
        val out = call(b, "doctor", mapOf("FILEAPEX_UNAME" to "Linux", "PATH" to "${bin.absolutePath}:${System.getenv("PATH")}")).out
        assertTrue(out, out.contains("Linux over SSH") && out.contains("Linux connection"))
        val calls = File(b.root, "calls.log").readText()
        assertFalse(calls, calls.contains("git checkout") || calls.contains("rm "))
    }

    @Test fun theWindowsLauncherOnlyFindsGitBashAndRunsTheOneScript() {
        val ps1 = File("../scripts/build-fileapex.ps1")
        assertTrue("scripts/build-fileapex.ps1 is missing", ps1.isFile)
        val text = ps1.readText()
        assertTrue("a launcher, not a second implementation", text.lines().size <= 40)
        assertTrue(text.contains("build-fileapex.sh") && text.contains("bash.exe") && text.contains("Git for Windows"))
        assertTrue(text.contains("@args") || text.contains("\$args"))
        assertTrue("it must pass the exit code on", text.contains("\$LASTEXITCODE"))
        listOf("cliff", "gradlew", "ssh ").forEach { assertFalse("launcher holds build logic: $it", text.contains(it)) }
    }

    @Test fun gradlewBatReadsTheJdkVersionFromToolchainProperties() {
        val bat = File("../gradlew.bat").readText()
        assertTrue(bat.contains("toolchain.properties") && bat.contains("!TOOLCHAIN_JDK!"))
        assertTrue("delayed expansion is needed inside the if block", bat.contains("EnableDelayedExpansion"))
    }

    @Test fun aKeyPathMayStartWithHomeOrTildeAndIsExpandedWithoutRunningAnything() {
        val b = box(); repoWithHead(b); val bin = fakeBin(b)
        b.config.parentFile.mkdirs()
        b.config.writeText("LIN_MODE=ssh\nLIN_HOST=dev@linbox\nLIN_KEY=\$HOME/.ssh/id\nWIN_KEY=~/.ssh/win\n")
        call(b, "run_remote_linux 1.0 work", mapOf("PATH" to "${bin.absolutePath}:${System.getenv("PATH")}"))
        val calls = File(b.root, "calls.log").readText()
        assertTrue(calls, calls.contains("-i ${b.home.absolutePath}/.ssh/id"))
        assertEquals("${b.home.absolutePath}/.ssh/win", call(b, "key_path WIN").out.trim())
    }

    @Test fun completeFromAnotherSystemStillBuildsOnTheRemoteMachines() {
        val b = box(); repoWithHead(b); val bin = fakeBin(b)
        b.config.parentFile.mkdirs()
        b.config.writeText("WIN_MODE=ssh\nWIN_HOST=dev@winbox\nWIN_DIR=D:/src/FileApex\nLIN_MODE=ssh\nLIN_HOST=dev@linbox\nLIN_DIR=FileApex\n")
        val r = call(b, "run_complete_all_platforms 1.0 work", mapOf("FILEAPEX_UNAME" to "Linux", "PATH" to "${bin.absolutePath}:${System.getenv("PATH")}"))
        assertEquals(r.out, 0, r.code)
        assertTrue(r.out, r.out.contains("need a Mac"))
        val calls = File(b.root, "calls.log").readText()
        assertTrue(calls, calls.contains("dev@winbox") && calls.contains("dev@linbox"))
        assertTrue(File(b.project, "current/FileApex-v1.0.exe").isFile && File(b.project, "current/FileApex-v1.0-amd64.deb").isFile)
        assertFalse(r.out, r.out.contains("Not built"))
    }

    @Test fun theCmdLauncherRunsThePowerShellLauncherWithoutNeedingAScriptPolicy() {
        val cmd = File("../scripts/build-fileapex.cmd")
        assertTrue("scripts/build-fileapex.cmd is missing", cmd.isFile)
        val text = cmd.readText()
        assertTrue(text.lines().filter { it.isNotBlank() }.size <= 6)
        assertTrue(text.contains("-ExecutionPolicy Bypass") && text.contains("build-fileapex.ps1") && text.contains("%~dp0") && text.contains("%*"))
        assertTrue(text.contains("ERRORLEVEL"))
        assertTrue("cmd files need CRLF", text.contains("\r\n"))
    }

    private val macSettings = "MAC_MODE=ssh\nMAC_HOST=dev@macbox\nMAC_DIR=src/FileApex\nMAC_KEY=~/.ssh/mac\nREMOTE=mirror\n"

    @Test fun completeOnAMacNeverUsesTheMacSshSetting() {
        val b = box(); repoWithHead(b); val bin = fakeBin(b)
        b.config.parentFile.mkdirs(); b.config.writeText(macSettings)
        File(b.project, "gradlew").writeText("#!/bin/bash\necho \"gradle \$*\" >> '${File(b.root, "gradle.log").absolutePath}'\nexit 0\n")
        call(b, "run_complete_all_platforms 1.0 work", mapOf("FILEAPEX_UNAME" to "Darwin", "PATH" to "${bin.absolutePath}:${System.getenv("PATH")}"))
        assertTrue(File(b.root, "gradle.log").isFile)
        assertFalse(File(b.root, "calls.log").exists() && File(b.root, "calls.log").readText().contains("macbox"))
    }

    @Test fun doctorChecksAConfiguredMacOverSsh() {
        val b = box(); val bin = fakeBin(b)
        b.config.parentFile.mkdirs(); b.config.writeText(macSettings)
        val out = call(b, "doctor", mapOf("FILEAPEX_UNAME" to "Linux", "PATH" to "${bin.absolutePath}:${System.getenv("PATH")}")).out
        assertTrue(out, out.contains("Mac over SSH") && out.contains("Mac signing settings"))
    }

    @Test fun theWindowsDoctorChecksInnoWhereTheGradleBuildLooksAndReportsRustAsOptional() {
        val b = box()
        File(b.home, "AppData/Local/Programs/Inno Setup 6").mkdirs()
        File(b.home, "AppData/Local/Programs/Inno Setup 6/ISCC.exe").writeText("x")
        val with = call(b, "doctor", mapOf("FILEAPEX_UNAME" to "MINGW64_NT-10.0")).out
        assertTrue(with, with.lines().any { it.contains("Inno Setup") && it.contains("✔") })
        assertTrue("Rust is reported, as optional, because native/dropweb and the Iroh rebuild use it", with.lines().any { it.contains("Rust (optional)") })
        val linux = call(b, "doctor", mapOf("FILEAPEX_UNAME" to "Linux")).out
        assertTrue(linux, linux.lines().any { it.contains("Rust (optional)") })
        val without = call(b, "doctor", mapOf("FILEAPEX_UNAME" to "MINGW64_NT-10.0", "HOME" to File(b.root, "nohome").absolutePath)).out
        assertTrue(without, without.lines().any { it.contains("Inno Setup") && it.contains("MISSING") })
        val gradle = File("../composeApp/build.gradle.kts").readText()
        assertTrue("doctor must look where the build looks", gradle.contains("AppData/Local/Programs/Inno Setup 6/ISCC.exe"))
    }

    @Test fun anOptionalToolThatIsMissingDoesNotFailTheDoctor() {
        val b = box()
        val optional = call(b, "DOCTOR_MISSING=0; doctor_check 'Android SDK (optional)' 0 d fix; echo missing=\$DOCTOR_MISSING")
        assertTrue(optional.out, optional.out.contains("missing=0") && optional.out.contains("optional"))
        val required = call(b, "DOCTOR_MISSING=0; doctor_check 'Go' 0 d fix; echo missing=\$DOCTOR_MISSING")
        assertTrue(required.out, required.out.contains("missing=1"))
    }

    private fun wizard(b: Box, target: String, answers: String, extra: Map<String, String> = emptyMap()) =
        call(b, "printf '$answers' | machine_wizard $target", extra)

    @Test fun theWizardAsksInPlainWordsAndNeverShowsHostJargon() {
        val b = box()
        val r = wizard(b, "WIN", "2\n", mapOf("FILEAPEX_UNAME" to "Linux"))
        assertTrue(r.out, r.out.contains("another computer") && r.out.contains("Do not build"))
        assertFalse(r.out, r.out.contains("local/ssh/off") || r.out.contains("user@host"))
    }

    @Test fun localWindowsNeverAsksForMsysWhenItIsThere() {
        val b = box()
        val msys = File(b.root, "msys").also { it.mkdirs() }; File(msys, "gcc.exe").writeText("x")
        b.config.parentFile.mkdirs(); b.config.writeText("WIN_MSYS=${msys.absolutePath}\n")
        val r = wizard(b, "WIN", "1\n\n", mapOf("FILEAPEX_UNAME" to "MINGW64_NT-10.0"))
        assertFalse(r.out, r.out.contains("MSYS2 gcc folder"))
        assertEquals("local", call(b, "config_get WIN_MODE").out.trim())
    }

    @Test fun localWindowsExplainsMsysOnlyWhenItIsMissing() {
        val b = box()
        val r = wizard(b, "WIN", "1\n\n\n", mapOf("FILEAPEX_UNAME" to "MINGW64_NT-10.0"))
        assertTrue(r.out, r.out.contains("msys2.org") && r.out.contains("C compiler"))
        assertEquals("local", call(b, "config_get WIN_MODE").out.trim())
    }

    @Test fun linuxOverSshDoesNotAskForGoWhenTheMachineHasIt() {
        val b = box(); val bin = fakeBin(b)
        val r = wizard(b, "LIN", "1\ndev@linbox\nFileApex\n\n\n", mapOf("FILEAPEX_UNAME" to "Darwin", "PATH" to "${bin.absolutePath}:${System.getenv("PATH")}"))
        assertFalse(r.out, r.out.contains("Go folder"))
        assertFalse(r.out, r.out.contains("JDK"))
        assertEquals("ssh", call(b, "config_get LIN_MODE").out.trim())
        assertEquals("dev@linbox", call(b, "config_get LIN_HOST").out.trim())
        assertEquals("", call(b, "config_get LIN_GO").out.trim())
    }

    @Test fun linuxOverSshAsksForGoOnlyWhenTheMachineLacksIt() {
        val b = box(); val bin = fakeBin(b)
        val r = wizard(b, "LIN", "1\ndev@linbox\nFileApex\n\n~/sdk/go\n\n", mapOf("FILEAPEX_UNAME" to "Darwin", "FAKE_SSH_FALSE" to "1", "PATH" to "${bin.absolutePath}:${System.getenv("PATH")}"))
        assertTrue(r.out, r.out.contains("Go folder") && r.out.contains("go.dev"))
        assertEquals("~/sdk/go", call(b, "config_get LIN_GO").out.trim())
    }

    @Test fun theGitRemoteIsOnlyAskedWhenThereIsAChoice() {
        val b = box(); val bare = File(b.root, "r.git"); sh("git init -q --bare '${bare.absolutePath}'")
        git(b.project, "init", "-q", "-b", "work")
        git(b.project, "remote", "add", "origin", bare.absolutePath)
        val one = call(b, "printf '' | choose_git_remote; config_get REMOTE")
        assertFalse(one.out, one.out.contains("Which git remote"))
        assertEquals("origin", one.out.trim())
        git(b.project, "remote", "add", "mirror", bare.absolutePath)
        val two = call(b, "printf '1\\n' | choose_git_remote; config_get REMOTE")
        assertTrue(two.out, two.out.contains("Which git remote") && two.out.trim().endsWith("mirror"))
    }

    private val androidSettings = "ANDROID_MODE=ssh\nANDROID_HOST=dev@androidbox\nANDROID_DIR=src/FileApex\nREMOTE=mirror\n"
    private fun withFakes(b: Box) = mapOf("PATH" to "${fakeBin(b).absolutePath}:${System.getenv("PATH")}")

    @Test fun theMacBuildMakesOnlyTheDmgsAndEveryFileIsVerified() {
        val b = box(); val head = repoWithHead(b)
        b.config.parentFile.mkdirs(); b.config.writeText(macSettings)
        val r = call(b, "run_remote_signed MAC 1.0 work", withFakes(b))
        assertEquals(r.out, 0, r.code)
        val calls = File(b.root, "calls.log").readText()
        assertTrue(calls, calls.contains("-i ${b.home.absolutePath}/.ssh/mac") && calls.contains("dev@macbox"))
        assertTrue(calls, calls.contains("bash build_remote_signed.sh 'work' '$head' 'mirror' 'src/FileApex' '-mac' 'FileApex-v1.0-Silicon.dmg' 'FileApex-v1.0-Intel.dmg'"))
        listOf("FileApex-v1.0-Silicon.dmg", "FileApex-v1.0-Intel.dmg").forEach { assertTrue("$it missing", File(b.project, "current/$it").isFile) }
        assertFalse(File(b.project, "current/FileApex-v1.0.apk").exists())
    }

    @Test fun theAndroidBuildMakesTheApksAndTheBundleAndEveryFileIsVerified() {
        val b = box(); val head = repoWithHead(b)
        b.config.parentFile.mkdirs(); b.config.writeText(androidSettings)
        val r = call(b, "run_remote_signed ANDROID 1.0 work", withFakes(b))
        assertEquals(r.out, 0, r.code)
        val calls = File(b.root, "calls.log").readText()
        assertTrue(calls, calls.contains("'-android' 'FileApex-v1.0.apk' 'FileApex-v1.0-armeabi-v7a.apk' 'FileApex-v1.0-release.aab'") && calls.contains("'$head'"))
        listOf("FileApex-v1.0.apk", "FileApex-v1.0-armeabi-v7a.apk", "FileApex-v1.0-release.aab").forEach { assertTrue("$it missing", File(b.project, "current/$it").isFile) }
        assertFalse(File(b.project, "current/FileApex-v1.0-Silicon.dmg").exists())
    }

    @Test fun theGeneratedSignedBuildScriptChecksTheCheckoutAndRemovesOnlyItsOwnFiles() {
        val mac = scriptFile.readText().substringAfter("build_remote_signed.sh\" <<'EOF'").substringBefore("\nEOF")
        assertTrue(mac.contains("git status --porcelain") && mac.contains("git fetch \"${'$'}REMOTE\"") && mac.contains("rev-parse HEAD"))
        assertTrue(mac.contains("scripts/build-fileapex.sh \"${'$'}FLAG\""))
        assertFalse("never remove folders", mac.contains("rm -rf") || mac.contains("rm -r "))
        assertTrue(mac.contains("rm -f \"current/${'$'}f\""))
    }

    @Test fun anUnsafeSignedBuildFolderIsRefusedBeforeAnythingIsSent() {
        val b = box(); repoWithHead(b)
        b.config.parentFile.mkdirs(); b.config.writeText("MAC_MODE=ssh\nMAC_HOST=dev@macbox\nMAC_DIR=\"a b;c\"\n")
        val r = call(b, "run_remote_signed MAC 1.0 work", withFakes(b))
        assertTrue(r.out, r.code != 0)
        assertFalse(File(b.root, "calls.log").exists())
    }

    @Test fun completeFromWindowsUsesAMacOverSshForTheDmgsOnly() {
        val b = box(); repoWithHead(b)
        b.config.parentFile.mkdirs(); b.config.writeText(macSettings)
        val r = call(b, "run_complete_all_platforms 1.0 work", withFakes(b) + mapOf("FILEAPEX_UNAME" to "MINGW64_NT-10.0"))
        assertEquals(r.out, 0, r.code)
        assertTrue(File(b.project, "current/FileApex-v1.0-Silicon.dmg").isFile)
        assertFalse(File(b.project, "current/FileApex-v1.0.apk").exists())
        assertTrue(r.out, r.out.contains("Android release steps skipped"))
        assertTrue(r.out, r.out.contains("Not built (not configured): Windows Linux"))
    }

    @Test fun completeFromWindowsUsesAnAndroidMachineForTheApksAndTheBundle() {
        val b = box(); repoWithHead(b)
        b.config.parentFile.mkdirs(); b.config.writeText(androidSettings)
        val r = call(b, "run_complete_all_platforms 1.0 work", withFakes(b) + mapOf("FILEAPEX_UNAME" to "MINGW64_NT-10.0"))
        assertEquals(r.out, 0, r.code)
        assertTrue(File(b.project, "current/FileApex-v1.0-release.aab").isFile)
        assertTrue(r.out, r.out.contains("Mac apps skipped") && r.out.contains("need a Mac"))
    }

    @Test fun androidCanBuildOnTheMachineRunningTheScriptEvenWhenItIsNotAMac() {
        val b = box(); repoWithHead(b)
        b.config.parentFile.mkdirs(); b.config.writeText("ANDROID_MODE=local\n")
        val log = File(b.root, "gradle.log")
        File(b.project, "gradlew").writeText("#!/bin/bash\necho \"gradle \$*\" >> '${log.absolutePath}'\nexit 0\n")
        val r = call(b, "run_complete_all_platforms 1.0 work", mapOf("FILEAPEX_UNAME" to "MINGW64_NT-10.0"))
        assertEquals(r.out, 0, r.code)
        val g = log.readText()
        assertTrue(g, g.contains("shipGithubRelease") && g.contains("bundlePlayRelease") && !g.contains("packageBothDmg"))
    }

    @Test fun aMacCanSendAndroidToAnotherComputerAndKeepsTheDmgsLocal() {
        val b = box(); repoWithHead(b)
        b.config.parentFile.mkdirs(); b.config.writeText(androidSettings)
        val log = File(b.root, "gradle.log")
        File(b.project, "gradlew").writeText("#!/bin/bash\necho \"gradle \$*\" >> '${log.absolutePath}'\nexit 0\n")
        val r = call(b, "run_complete_all_platforms 1.0 work", withFakes(b) + mapOf("FILEAPEX_UNAME" to "Darwin"))
        assertEquals(r.out, 0, r.code)
        val g = log.readText()
        assertTrue(g, g.contains("packageBothDmg") && !g.contains("shipGithubRelease") && !g.contains("bundlePlayRelease"))
        assertTrue(File(b.project, "current/FileApex-v1.0.apk").isFile)
    }

    @Test fun aMacWithNoAndroidSettingKeepsBuildingAndroidItselfAsBefore() {
        val b = box(); repoWithHead(b)
        b.config.parentFile.mkdirs(); b.config.writeText("REMOTE=origin\n")
        val log = File(b.root, "gradle.log")
        File(b.project, "gradlew").writeText("#!/bin/bash\necho \"gradle \$*\" >> '${log.absolutePath}'\nexit 0\n")
        call(b, "run_complete_all_platforms 1.0 work", mapOf("FILEAPEX_UNAME" to "Darwin"))
        val g = log.readText()
        assertTrue(g, g.contains("shipGithubRelease packageBothDmg verifyReleaseSigning verifyReleaseApkSigned :verifyGitExecutableScripts") && g.contains("bundlePlayRelease"))
    }

    @Test fun twoSignedBuildsOnTheSameComputerRunOneAfterTheOther() {
        val b = box(); repoWithHead(b)
        b.config.parentFile.mkdirs()
        b.config.writeText("MAC_MODE=ssh\nMAC_HOST=dev@same\nMAC_DIR=src/FileApex\nANDROID_MODE=ssh\nANDROID_HOST=dev@same\nANDROID_DIR=src/FileApex\n")
        val r = call(b, "run_complete_all_platforms 1.0 work", withFakes(b) + mapOf("FILEAPEX_UNAME" to "Linux"))
        assertEquals(r.out, 0, r.code)
        val calls = File(b.root, "calls.log").readText()
        assertTrue(calls, calls.indexOf("'-mac'") in 0 until calls.indexOf("'-android'"))
        val firstAndroidFetch = calls.indexOf("FileApex-v1.0.apk")
        assertTrue("android must start after the Mac files were fetched", calls.lastIndexOf("Intel.dmg") < calls.indexOf("'-android'") || firstAndroidFetch > calls.indexOf("Intel.dmg"))
    }

    @Test fun androidIsItsOwnGroupAndMacIsOnlyTheMacApps() {
        val b = box()
        val android = wizard(b, "ANDROID", "3\n", mapOf("FILEAPEX_UNAME" to "Linux"))
        assertTrue(android.out, android.out.contains("Android apps") && android.out.contains("Build it on this computer"))
        val mac = wizard(b, "MAC", "2\n", mapOf("FILEAPEX_UNAME" to "Linux"))
        assertTrue(mac.out, mac.out.contains("Mac apps") && !mac.out.contains("Android"))
    }

    @Test fun doctorChecksAConfiguredAndroidComputerOverSsh() {
        val b = box()
        b.config.parentFile.mkdirs(); b.config.writeText(androidSettings)
        val out = call(b, "doctor", withFakes(b) + mapOf("FILEAPEX_UNAME" to "Linux")).out
        assertTrue(out, out.contains("Android over SSH") && out.contains("Android signing settings"))
    }

    @Test fun aChangedReadmeDoesNotStopARemoteBuildButAChangedSourceFileDoes() {
        val b = box()
        File(b.project, "README.md").writeText("readme\n")
        File(b.project, "app.kt").writeText("fun main() {}\n")
        val git = "git -c user.name=t -c user.email=t@t -c commit.gpgsign=false"
        assertEquals(0, sh("git init -q && $git add -A && $git commit -q -m init", dir = b.project).code)
        File(b.project, "README.md").writeText("edited\n")
        assertEquals("", call(b, "build_relevant_changes").out.trim())
        File(b.project, "app.kt").writeText("fun main() { println() }\n")
        assertEquals("app.kt", call(b, "build_relevant_changes").out.trim())
    }
}

