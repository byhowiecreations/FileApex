package com.fileapex.network.routes

import com.fileapex.network.FileApexServer
import com.fileapex.network.PeerHttpHeaders
import io.ktor.server.application.ApplicationCall

/** The removal fingerprint and the cluster digest, for the answer to a peer that proved who it is. */
internal suspend fun ApplicationCall.appendClusterHeaders(server: FileApexServer) {
    runCatching { server.removalFingerprint() }.getOrNull()?.let { response.headers.append(PeerHttpHeaders.REMOVALS, it) }
    runCatching { server.clusterDigest() }.getOrNull()?.let { response.headers.append(PeerHttpHeaders.CLUSTER_DIGEST, it) }
}
