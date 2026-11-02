package com.fileapex.network.routes

import com.fileapex.membership.IdentityChangeAnswer
import com.fileapex.membership.IdentityChangeInbox
import com.fileapex.membership.IdentityChangeRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Status code and JSON for the id change routes. [certificateDevice] and [connectionPin] come from the authenticated connection, never from the body. */
internal object IdentityChangeRouteHandlers {
    suspend fun receive(inbox: IdentityChangeInbox, certificateDevice: String, connectionPin: String, body: String, json: Json): Pair<Int, String> {
        val request = try {
            json.decodeFromString(IdentityChangeRequest.serializer(), body)
        } catch (_: SerializationException) {
            return 400 to ""
        } catch (_: IllegalArgumentException) {
            return 400 to ""
        }
        if (request.oldId != certificateDevice) return 403 to encode(json, IdentityChangeAnswer("refused", reason = "NOT_THIS_CERTIFICATE"))
        return when (val intake = inbox.receive(request, connectionPin)) {
            is IdentityChangeInbox.Intake.Waiting -> 200 to encode(json, IdentityChangeAnswer("waiting", intake.code))
            is IdentityChangeInbox.Intake.Refused -> 403 to encode(json, IdentityChangeAnswer("refused", reason = intake.reason.name))
        }
    }

    fun status(inbox: IdentityChangeInbox, oldId: String, certificateDevice: String, nonce: String, json: Json): String {
        val status = if (oldId == certificateDevice) inbox.status(oldId, nonce) else IdentityChangeInbox.Status.UNKNOWN
        return encode(json, IdentityChangeAnswer(status.name.lowercase()))
    }

    private fun encode(json: Json, answer: IdentityChangeAnswer) = json.encodeToString(IdentityChangeAnswer.serializer(), answer)
}
