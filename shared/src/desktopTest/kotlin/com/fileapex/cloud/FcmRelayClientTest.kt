package com.fileapex.cloud

import com.fileapex.network.FileApexHttpClientFactory
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FcmRelayClientTest {
    private lateinit var server: HttpServer
    private var status = 200
    private var requests = 0
    private var lastAuth: String? = null
    private var lastBody: String = ""
    private val client by lazy { FileApexHttpClientFactory.create() }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/wake") { exchange ->
            requests++
            lastAuth = exchange.requestHeaders.getFirst("Authorization")
            lastBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val reply = """{"ok":true}""".toByteArray()
            exchange.sendResponseHeaders(status, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        client.close()
    }

    private fun relay(token: String? = "firebase-id-token") = FcmRelayClient(
        httpClient = { client },
        idToken = { token },
        endpoint = "http://127.0.0.1:${server.address.port}/api/wake"
    )

    private fun wake(vararg fields: Pair<String, String>): JsonObject = buildJsonObject {
        fields.forEach { (key, value) -> put(key, value) }
    }

    @Test
    fun sendsTheTargetTokenAndDataWithTheFirebaseToken() = runBlocking {
        val sent = relay().sendDataWake("target-fcm-token", wake("type" to "presence_wake", "sourceDeviceId" to "d1"))
        assertTrue(sent)
        assertEquals("Bearer firebase-id-token", lastAuth)
        val body = Json.parseToJsonElement(lastBody).jsonObject
        assertEquals("target-fcm-token", body.getValue("token").jsonPrimitive.content)
        val data = body.getValue("data").jsonObject
        assertEquals("presence_wake", data.getValue("type").jsonPrimitive.content)
        assertEquals("d1", data.getValue("sourceDeviceId").jsonPrimitive.content)
    }

    @Test
    fun numbersInTheDataGoOutAsStringsBecauseTheRelayAcceptsOnlyStrings() = runBlocking {
        val data = JsonObject(mapOf("type" to JsonPrimitive("note_sync"), "epochMs" to JsonPrimitive(12345L)))
        assertTrue(relay().sendDataWake("target-fcm-token", data))
        val sentData = Json.parseToJsonElement(lastBody).jsonObject.getValue("data").jsonObject
        assertTrue(sentData.getValue("epochMs").jsonPrimitive.isString)
        assertEquals("12345", sentData.getValue("epochMs").jsonPrimitive.content)
    }

    @Test
    fun withoutASignInNothingIsSent() = runBlocking {
        assertFalse(relay(token = null).sendDataWake("target-fcm-token", wake("type" to "presence_wake")))
        assertEquals(0, requests)
    }

    @Test
    fun aRefusalFromTheRelayCountsAsNotSent() = runBlocking {
        for (code in listOf(400, 401, 410, 429, 502)) {
            status = code
            assertFalse("status $code", relay().sendDataWake("target-fcm-token", wake("type" to "presence_wake")))
        }
        assertEquals(5, requests)
    }
}
