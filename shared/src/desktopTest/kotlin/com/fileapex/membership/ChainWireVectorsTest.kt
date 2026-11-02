package com.fileapex.membership

import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.network.FileApexHttpClientFactory
import java.io.File
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chain routes' JSON, written by Kotlin and by the Go daemon and read by the other. [writeTheWire] makes the Kotlin
 * file (set KOTLIN_WIRE_OUT); [theGoWireIsReadByKotlin] reads the file the daemon wrote (GO_WIRE_OUT in its test).
 */
class ChainWireVectorsTest {
    @Serializable
    data class Wire(val answer: ChainAnswer, val push: ChainPush, val join: ChainJoinRequest)

    private val json = FileApexHttpClientFactory.defaultJson
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val goFile = File(root, "native/tsnet/daemon/testdata/go_chain_wire.json")

    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
    }

    private fun profile(id: String, name: String) = PeerNodeState(
        deviceId = id, deviceName = name, ipAddress = "192.168.1.20", port = 8080, platform = "android", os = "android",
        rootPath = "/storage/emulated/0", publicKey = "pairkey", tlsPin = "a".repeat(64), tlsPort = 8081
    )

    @Test
    fun writeTheWire() = runBlocking {
        val out = System.getenv("KOTLIN_WIRE_OUT") ?: return@runBlocking
        val a = Dev("device-a"); val b = Dev("device-b")
        val log = MembershipChainLog(InMemoryChainStore())
        log.append(a.signer, RecordKind.GENESIS, a.id, a.signer.publicKey, "a".repeat(64), "paired")
        log.append(a.signer, RecordKind.ADMIT, b.id, b.signer.publicKey, "b".repeat(64), "paired")
        val records = log.recordsAfter(0L)
        val wire = Wire(
            ChainAnswer(log.view().state.head, records, listOf(profile("device-a", "Pixel"))),
            ChainPush(records, listOf(profile("device-b", "Laptop"))),
            ChainJoinRequest(b.signer.publicKey, "b".repeat(64), profile("device-b", "Laptop"))
        )
        File(out).writeText(Json2.pretty.encodeToString(Wire.serializer(), wire))
    }

    @Test
    fun theGoWireIsReadByKotlin() {
        assertTrue("the Go wire file is missing: ${goFile.path}", goFile.exists())
        val wire = json.decodeFromString(Wire.serializer(), goFile.readText())
        assertTrue(wire.answer.records.size >= 2)
        assertEquals(wire.answer.head, wire.answer.records.last().hash())
        (wire.answer.records + wire.push.records).forEach { assertTrue("record ${it.seq} verifies", ChainRecords.verify(it)) }
        assertTrue(wire.answer.profiles.isNotEmpty())
        assertTrue(wire.answer.profiles.first().tlsPort > 0)
        assertNotNull(wire.join.profile)
        assertTrue(wire.join.key.isNotEmpty())
    }

    private object Json2 {
        val pretty = kotlinx.serialization.json.Json(from = FileApexHttpClientFactory.defaultJson) { prettyPrint = true; encodeDefaults = true }
    }
}
