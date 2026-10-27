package com.fileapex.network

/** Response headers of a peer call. Names are lower case, so a lookup never depends on how the peer wrote them. */
object PeerHttpHeaders {
    /** Sent by a node on its health answer: the node's own membership version, so a prober sees a change without a second request. */
    const val MEMBERSHIP_VERSION = "X-FileApex-Membership-Version"

    /** Latest change in the node's bulletin board, on its own clock; a peer behind it pulls the difference. */
    const val BULLETIN_VERSION = "X-FileApex-Bulletin-Version"

    /** Fingerprint of the removal records a node holds, on its health answer to a proven peer (see RemovalFingerprint). */
    const val REMOVALS = "X-FileApex-Removals"

    fun removals(headers: Map<String, String>): String? = headers[REMOVALS.lowercase()]

    fun bulletinVersion(headers: Map<String, String>): Long? =
        headers[BULLETIN_VERSION.lowercase()]?.toLongOrNull()

    fun parse(headerBlock: String): Map<String, String> = parseLines(headerBlock.lineSequence().drop(1).joinToString("\n"))

    /** Header lines without a status line, as the native Mac client returns them. */
    fun parseLines(text: String): Map<String, String> {
        val headers = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return headers
    }

    fun membershipVersion(headers: Map<String, String>): Long? =
        headers[MEMBERSHIP_VERSION.lowercase()]?.toLongOrNull()
}
