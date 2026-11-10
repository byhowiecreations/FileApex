package com.fileapex.cloud.device

import com.fileapex.membership.ChainRecord
import com.fileapex.membership.RecordKind
import io.ktor.client.HttpClient
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The chain records sent to the relay carry every signed field under the names the relay reads (functions/_lib/chain.js). */
class DeviceRelayClientJsonTest {
    @Test
    fun aRecordIsSentWithEveryFieldTheRelayVerifies() {
        val record = ChainRecord(7L, "parent", RecordKind.ADMIT, "actor", "actorKey", "subject", "subjectKey", "a".repeat(64), "paired", "sig")
        val sent = DeviceRelayClient({ HttpClient() }, { null }).recordsJson(listOf(record)).single().jsonObject
        assertEquals(
            listOf("7", "parent", "ADMIT", "actor", "actorKey", "subject", "subjectKey", "a".repeat(64), "paired", "sig"),
            listOf("seq", "parentHash", "kind", "actor", "actorKey", "subject", "subjectKey", "tlsPin", "reason", "signature").map { sent[it]!!.jsonPrimitive.content }
        )
    }
}
