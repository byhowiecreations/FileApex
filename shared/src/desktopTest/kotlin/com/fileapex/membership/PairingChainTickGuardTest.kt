package com.fileapex.membership

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pairing must tick the chain at once so the new member is admitted without waiting for the minute schedule. */
class PairingChainTickGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun theJoinerTicksTheChainWhenPairingCompletes() {
        val text = File(root, "shared/src/commonMain/kotlin/com/fileapex/presentation/devices/DevicesPairingActions.kt").readText()
        assertTrue("DevicesPairingActions never joins the host's cluster after pairing", "onJoinedHost" in text)
    }

    @Test
    fun theHostTicksTheChainWhenPairingCompletes() {
        val text = File(root, "shared/src/commonMain/kotlin/com/fileapex/network/ServerLifecycleManager.kt").readText()
        assertTrue("ServerLifecycleManager never ticks the chain after pairing", "onPaired" in text)
    }
}
