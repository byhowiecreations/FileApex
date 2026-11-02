package com.fileapex.membership

import java.io.File
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signed chain that the Go daemon is checked against. [writeTheVectors] makes the file (set CHAIN_VECTORS_OUT to its
 * path); [theCommittedVectorsStillHoldForKotlin] fails when a change to the records, the hash or the rules makes the
 * committed file wrong, so the Go side is never left behind.
 */
class ChainVectorsTest {
    @Serializable
    data class Vector(val records: List<VectorRecord>, val views: List<VectorView>)

    @Serializable
    data class VectorRecord(val record: ChainRecord, val hash: String)

    @Serializable
    data class VectorMember(val key: String, val tlsPin: String, val active: Boolean)

    @Serializable
    data class VectorView(val self: String, val canonical: List<String>, val served: List<String>, val members: Map<String, VectorMember>)

    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
    }

    private val json = Json { encodeDefaults = true; prettyPrint = true }
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val file = File(root, "native/tsnet/daemon/testdata/chain_vectors.json")

    private fun pin(c: Char) = c.toString().repeat(64)

    private suspend fun viewOf(records: List<ChainRecord>, self: String): VectorView {
        val store = InMemoryChainStore()
        records.forEach { store.put(it) }
        val log = MembershipChainLog(store) { self }
        val view = log.view()
        return VectorView(
            self = self,
            canonical = view.canonical.map { it.hash() },
            served = log.recordsAfter(0L).map { it.hash() },
            members = view.state.members.mapValues { (_, m) -> VectorMember(m.key, m.tlsPin, m.active) }
        )
    }

    @Test
    fun writeTheVectors() = runBlocking {
        val out = System.getenv("CHAIN_VECTORS_OUT") ?: return@runBlocking
        val a = Dev("device-a"); val b = Dev("device-b"); val c = Dev("device-c"); val d = Dev("device-d"); val e = Dev("device-e")
        val b2 = Dev("device-b")
        val log = MembershipChainLog(InMemoryChainStore())
        log.append(a.signer, RecordKind.GENESIS, a.id, a.key, pin('a'), "paired")
        log.append(a.signer, RecordKind.ADMIT, b.id, b.key, pin('b'), "paired")
        log.append(a.signer, RecordKind.ADMIT, c.id, "legacy:${c.id}", pin('c'), "paired")
        log.append(b.signer, RecordKind.TLS_PIN, b.id, b.key, pin('1'), "tls_pin")
        log.append(a.signer, RecordKind.ADMIT, c.id, c.key, pin('c'), "paired")
        val beforeRemoval = log.view().state
        log.append(a.signer, RecordKind.REMOVE, b.id, b.key, pin('1'), "user_removed")
        log.append(a.signer, RecordKind.ADMIT, b.id, b2.key, pin('2'), "paired")
        log.append(c.signer, RecordKind.LEAVE, c.id, c.key, pin('c'), "left_cluster")
        val main = log.recordsAfter(0L)
        // A fork at the removal: an admit of another device at the same place loses to the removal.
        val forkAdmit = ChainRecords.sign(
            a.signer,
            ChainRecord(beforeRemoval.seq + 1, beforeRemoval.head, RecordKind.ADMIT, a.id, a.key, e.id, e.key, pin('e'), "paired")
        )
        // Another cluster: its own genesis and an admit of a.
        val otherGenesis = ChainRecords.sign(d.signer, ChainRecord(1, "", RecordKind.GENESIS, d.id, d.key, d.id, d.key, pin('d'), "paired"))
        val otherAdmit = ChainRecords.sign(
            d.signer, ChainRecord(2, otherGenesis.hash(), RecordKind.ADMIT, d.id, d.key, a.id, a.key, pin('a'), "paired")
        )
        val all = main + forkAdmit + otherGenesis + otherAdmit
        val views = listOf("device-a", "device-d", "device-x").map { viewOf(all, it) }
        File(out).writeText(json.encodeToString(Vector.serializer(), Vector(all.map { VectorRecord(it, it.hash()) }, views)))
    }

    @Test
    fun theCommittedVectorsStillHoldForKotlin() = runBlocking {
        assertTrue("the vectors are missing: run writeTheVectors with CHAIN_VECTORS_OUT=${file.path}", file.isFile)
        val vector = Json { ignoreUnknownKeys = true }.decodeFromString(Vector.serializer(), file.readText())
        for (entry in vector.records) {
            assertEquals("hash of ${entry.record.kind} ${entry.record.seq}", entry.hash, entry.record.hash())
            assertTrue("signature of ${entry.record.kind} ${entry.record.seq}", ChainRecords.verify(entry.record))
        }
        val records = vector.records.map { it.record }
        for (expected in vector.views) {
            assertEquals("view for ${expected.self}", expected, viewOf(records, expected.self))
        }
    }
}
