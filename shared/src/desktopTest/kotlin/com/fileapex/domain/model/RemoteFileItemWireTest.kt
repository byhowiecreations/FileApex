package com.fileapex.domain.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RemoteFileItemWireTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun encodedItemOmitsId() {
        val item = RemoteFileItem("a.txt", "/root/a.txt", 3L, 4L, false, "text/plain")
        assertFalse(json.encodeToString(item).contains("\"id\""))
    }

    @Test
    fun legacyPayloadWithIdStillDecodes() {
        val legacy = """{"id":"/root/a.txt","name":"a.txt","absolutePath":"/root/a.txt","sizeBytes":3,"lastModified":4,"isDirectory":false,"mimeType":"text/plain"}"""
        val item = json.decodeFromString<RemoteFileItem>(legacy)
        assertEquals("/root/a.txt", item.id)
    }
}
