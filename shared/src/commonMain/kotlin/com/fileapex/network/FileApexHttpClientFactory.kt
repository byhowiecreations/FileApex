package com.fileapex.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Factory for the process-wide Ktor client used by pairing, updates, and desktop cloud.
 */
object FileApexHttpClientFactory {
    val defaultJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    fun create(json: Json = defaultJson): HttpClient {
        return HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 10 * 60 * 1000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 10 * 60 * 1000
            }
        }
    }
}
