package com.fileapex.cli.ipc

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CliRequestTest {

    @Test
    fun testCliRequestSerialization() {
        val json = Json { ignoreUnknownKeys = true }
        val req = CliRequest(args = listOf("help"), cwd = ".")
        val str = json.encodeToString(req)
        val decoded = json.decodeFromString<CliRequest>(str)
        assertEquals(req.args, decoded.args)
        assertEquals(req.cwd, decoded.cwd)
    }
}
