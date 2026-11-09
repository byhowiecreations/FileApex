package com.fileapex.data.seal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Nothing the app keeps may be written to disk in clear text by accident. Every file write and every Android
 * preferences file must either go through [SealedText] / [SealedStrings] / the sealed settings store, or be listed here with the reason
 * it holds nothing about the user and is read by something that cannot unseal it.
 */
class PlainWriteGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    /** Holds nothing about the user, or must be readable by something that cannot unseal it. */
    private val justified: Map<String, String> = mapOf(
        "TransferRanges.kt" to "partial transfer bookkeeping inside the part file's own folder, byte ranges only",
        "NotificationDebugLog.kt" to "debug log, switched off, redacted at the sink",
        "MacOsExtensionRegistrar.kt" to "registration stamp and a redacted log read by the system installer",
        "PlatformUpdateInstaller.desktop.kt" to "the updater's own shell script, no user data",
        "DesktopAutostart.kt" to "OS launcher entry read by the desktop environment",
        "WindowsGraphicsMode.kt" to "one word (automatic, direct3d or opengl) that must be readable before Skiko loads, long before any key can open; holds nothing about the user",
        "LinuxDesktopIntegration.kt" to "OS launcher entries read by the desktop environment",
        "CliPathIntegration.kt" to "shell wrapper scripts read by the shell",
        "DesktopLifecycleLog.kt" to "redacted lifecycle log, read by the user on purpose",
        "DesktopCrashHandler.kt" to "redacted crash log, read by the user on purpose",
        "DesktopNetworkingSmokeProbe.kt" to "probe file with the text ok",
        "DesktopI18nRuntime.kt" to "translations, public data",
        "FileApexDatabase.desktop.kt" to "marker file with the text ok",
        "ClusterResetPlatform.desktop.kt" to "marker file with the text ok",
        "ClusterResetPlatform.android.kt" to "only clears preferences",
        "ProcessExitLogger.kt" to "one timestamp of the last process exit",
        "AndroidOnboardingPermissions.kt" to "onboarding step flags",
        "BatteryBulletinCoordinator.kt" to "battery notice flags and timestamps",
        "ShareServerPendingStart.kt" to "one flag: the share server should start",
        "AndroidNotificationChannels.kt" to "two channel migration flags",
        "ServiceWatchdog.android.kt" to "clean-stop flags",
        "ServiceWatchdogScheduler.kt" to "device-protected mirrors that must be readable before first unlock, when no key can be used",
        "UpdateKeyValueStore.android.kt" to "pending update record: version codes and file names of the app's own APK",
        "AppSettings.android.kt" to "the settings store itself, wrapped by the sealing store",
        "AppSettings.desktop.kt" to "the settings store itself, wrapped by the sealing store",
        "GoogleDriveAuth.android.kt" to "the tokens are sealed by SealingKvStore; the rest of the file is flags",
        "SettingsSecretVaults.kt" to "the wrapping key of the vault itself",
        "TlsIdentityStore.kt" to "the identity files are sealed by the vault (through VaultFile); the rotation proof is public",
        "VaultFile.kt" to "writes only what the vault sealed, with the scheme byte in front",
        "SealedText.kt" to "the sealed text helper",
        "LocalApiFile.kt" to "local-api.json: a port and a per-launch token, owner-only; the extensions cannot unseal anything and it holds nothing about the user",
        "RestoreCredentials.kt" to "expect declaration only",
        "LocalIdentity.android.kt" to "writes the random device id and previous id in clear on purpose (the device name is sealed): a failed read of a sealed id would invent a new identity and break its binding to the E2EE key",
        "RestoreCredentials.android.kt" to "three flags; the credential itself is held by the system credential manager"
    )

    /** Still written in clear text and NOT acceptable: each has a roadmap row and goes when it is sealed. */
    private val open: Map<String, String> = mapOf(
        "FirebaseGate.kt" to "whether Google is linked, read at launch before the settings store opens"
    )

    private val allowed = justified + open

    @Test
    fun openEntriesNeverOverlapJustifiedOnes() {
        assertEquals(emptySet<String>(), justified.keys.intersect(open.keys))
    }

    @Test
    fun everyDiskWriteIsSealedOrListedWithAReason() {
        val offenders = listOf("shared/src", "composeApp/src").map { File(root, it) }.flatMap { dir ->
            dir.walkTopDown().onEnter { it.name != "build" }
                .filter { it.isFile && it.extension == "kt" && "Test" !in it.path && "androidTest" !in it.path }.toList()
        }.filter { file -> file.name !in allowed && PlainWriteScan.writesPlain(file.readText()) }.map { it.name }.sorted()
        assertEquals("these files write to disk in clear text; seal them with SealedText or SealedStrings (a real call, not a mention) or list them with a reason", emptyList<String>(), offenders)
    }
}
