package com.fileapex.network.routes

import com.fileapex.membership.ChainAnswer
import com.fileapex.membership.ChainJoinRequest
import com.fileapex.membership.ChainPush
import com.fileapex.membership.ChainReplicator
import com.fileapex.membership.ChainTick
import kotlinx.serialization.json.Json

/** A `since` or push head is a claim of the asker: only a full SHA-256 hex hash is used, anything else means "send everything". */
internal object ChainSince {
    private val HASH = Regex("^[0-9a-f]{64}$")

    fun clean(value: String): String = if (HASH.matches(value)) value else ""
}

/** The work of the chain routes, apart from the HTTP plumbing: a broken body throws, which the route answers with a 400. */
internal object ChainRouteHandlers {
    suspend fun push(replicator: ChainReplicator, from: String, body: String, json: Json): String {
        val push = json.decodeFromString(ChainPush.serializer(), body)
        return json.encodeToString(ChainAnswer.serializer(), replicator.receive(from, push.records, push.profiles, since = ChainSince.clean(push.head)))
    }

    suspend fun pull(replicator: ChainReplicator, json: Json, withProfiles: Boolean = true, since: String = ""): String =
        json.encodeToString(ChainAnswer.serializer(), replicator.serve(withProfiles, ChainSince.clean(since)))

    /** [from] and [pin] come from the authenticated connection; the pin in the body is a claim and is not used. */
    suspend fun join(tick: ChainTick, from: String, pin: String, body: String, rosterIds: Set<String>, json: Json): String {
        val request = json.decodeFromString(ChainJoinRequest.serializer(), body)
        return json.encodeToString(ChainAnswer.serializer(), tick.receiveJoin(from, request.key, pin, rosterIds, request.profile))
    }
}
