package com.fileapex.network

private const val REFUSAL_CHECK_TIMEOUT_MS = 400L

/** Tells the peer this device is leaving. True when it answered with a success; an older build answers 404 and is ignored. */
suspend fun FileApexClient.sendPresenceGoodbye(host: String, port: Int, timeoutMs: Long): Boolean {
    val response = boundPost(
        host = host,
        port = port,
        pathWithQuery = queryPath(basePath = "/api/v1/presence", host = host, port = port),
        body = """{"state":"leaving"}""",
        contentType = "application/json",
        timeoutMs = timeoutMs
    )
    return response.statusCode in 200..299
}

/**
 * A refusal comes back at once, a timeout takes the whole wait, so only a failure that returned well before the
 * timeout is looked at, with one short connect that tells a refusal from any other failure.
 */
internal suspend fun failedFastAndRefused(host: String, port: Int, elapsedMs: Long, timeoutMs: Long): Boolean {
    if (elapsedMs > timeoutMs / 2) return false
    return peerConnectRefused(host, port, REFUSAL_CHECK_TIMEOUT_MS)
}
