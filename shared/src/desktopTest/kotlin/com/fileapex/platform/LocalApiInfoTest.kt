package com.fileapex.platform

import com.fileapex.network.LocalApiToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalApiInfoTest {
    @Test fun payloadIsThePortAndTheLaunchToken() {
        val obj = Json.parseToJsonElement(LocalApiInfo.payload(8080)).jsonObject
        assertEquals(setOf("port", "token"), obj.keys)
        assertEquals("8080", obj["port"]!!.jsonPrimitive.content)
        assertEquals(LocalApiToken.current, obj["token"]!!.jsonPrimitive.content)
    }
}
