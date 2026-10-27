package com.fileapex.network

import com.fileapex.data.identity.LocalDeviceNameStore
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a device answers after it renamed itself: the name it actually applied. Builds before 0.19.8 answer with no body. */
@Serializable
data class RenameDeviceReply(val deviceName: String)

sealed interface RenameOutcome {
    data class Confirmed(val name: String) : RenameOutcome

    data object Unconfirmed : RenameOutcome

    data object NotDelivered : RenameOutcome
}

object RenameConfirmation {
    private val json = Json { ignoreUnknownKeys = true }

    /** A successful (200) answer: the reply when it parses, otherwise [RenameOutcome.Unconfirmed]; never an error. */
    fun fromBody(body: String): RenameOutcome {
        val name = runCatching { json.decodeFromString(RenameDeviceReply.serializer(), body).deviceName.trim() }.getOrNull()
        return if (name.isNullOrEmpty()) RenameOutcome.Unconfirmed else RenameOutcome.Confirmed(name)
    }
}

internal suspend fun ApplicationCall.respondRenamed(json: Json, requested: String) {
    val applied = LocalDeviceNameStore.current().ifBlank { requested }
    respondText(json.encodeToString(RenameDeviceReply.serializer(), RenameDeviceReply(applied)), ContentType.Application.Json)
}
