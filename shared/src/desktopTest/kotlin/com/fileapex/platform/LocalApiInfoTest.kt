package com.fileapex.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalApiInfoTest {
    @Test fun payloadIsJustThePort() {
        val obj = Json.parseToJsonElement(LocalApiInfo.payload(8080)).jsonObject
        assertEquals(setOf("port"), obj.keys)
        assertEquals("8080", obj["port"]!!.jsonPrimitive.content)
    }
}
