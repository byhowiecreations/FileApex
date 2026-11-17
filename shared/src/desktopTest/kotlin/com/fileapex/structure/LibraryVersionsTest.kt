package com.fileapex.structure

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the libraries upgraded in 0.21.3 so a stray resolution cannot quietly roll them back.
 * Ktor 3.6.1 requires kotlinx-coroutines 1.11.0 and kotlinx-serialization 1.11.0, so the three move together.
 */
class LibraryVersionsTest {
    private fun jarOf(type: Class<*>): String = type.protectionDomain.codeSource.location.path.substringAfterLast('/')

    @Test
    fun ktorIs361() {
        val jar = jarOf(io.ktor.server.application.Application::class.java)
        assertTrue("ktor-server-core is $jar", jar.startsWith("ktor-server-core-jvm-3.6.1"))
    }

    @Test
    fun coroutinesAre1110() {
        val jar = jarOf(kotlinx.coroutines.CoroutineScope::class.java)
        assertTrue("kotlinx-coroutines-core is $jar", jar.startsWith("kotlinx-coroutines-core-jvm-1.11.0"))
    }

    @Test
    fun serializationIs1110() {
        val jar = jarOf(kotlinx.serialization.json.Json::class.java)
        assertTrue("kotlinx-serialization-json is $jar", jar.startsWith("kotlinx-serialization-json-jvm-1.11.0"))
    }

    @Test
    fun jmdnsIs365() {
        val jar = jarOf(javax.jmdns.JmDNS::class.java)
        assertTrue("jmdns is $jar", jar.startsWith("jmdns-3.6.5"))
    }
}
