package com.fileapex.network.routes

import com.fileapex.membership.ChainAnswer
import com.fileapex.membership.ChainJoinRequest
import com.fileapex.membership.ChainPush
import com.fileapex.membership.ChainReplicator
import com.fileapex.membership.ChainTick
import kotlinx.serialization.json.Json

/** The work of the chain routes, apart from the HTTP plumbing: a broken body throws, which the route answers with a 400. */
internal object ChainRouteHandlers {
    suspend fun push(replicator: ChainReplicator, from: String, body: String, json: Json): String {
        val push = json.decodeFromString(ChainPush.serializer(), body)
        return json.encodeToString(ChainAnswer.serializer(), replicator.receive(from, push.records, push.profiles))
    }

    suspend fun pull(replicator: ChainReplicator, json: Json, withProfiles: Boolean = true): String =
        json.encodeToString(ChainAnswer.serializer(), replicator.serve(withProfiles))

    /** [from] and [pin] come from the authenticated connection; the pin in the body is a claim and is not used. */
    suspend fun join(tick: ChainTick, from: String, pin: String, body: String, rosterIds: Set<String>, json: Json): String {
        val request = json.decodeFromString(ChainJoinRequest.serializer(), body)
        return json.encodeToString(ChainAnswer.serializer(), tick.receiveJoin(from, request.key, pin, rosterIds, request.profile))
    }
}
