package com.fileapex.network

/** Response headers of a peer call. Names are lower case, so a lookup never depends on how the peer wrote them. */
object PeerHttpHeaders {
    /** Sent by a node on its health answer: the node's own membership version, so a prober sees a change without a second request. */
    const val MEMBERSHIP_VERSION = "X-FileApex-Membership-Version"

    /** Latest change in the node's bulletin board, on its own clock; a peer behind it pulls the difference. */
    const val BULLETIN_VERSION = "X-FileApex-Bulletin-Version"

    /** Sent on a paired peer's health answer: the device this node shows on Simple Home right now, so others skip it when they guess. */
    const val LINKED_TO = "X-FileApex-Linked-To"

    fun linkedTo(headers: Map<String, String>): String? =
        headers[LINKED_TO.lowercase()]?.trim()?.takeIf { it.isNotEmpty() }

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

    /** The peer's chain state, or null when it sent none or one that is not exactly a well-formed value. */
    fun chainState(headers: Map<String, String>): com.fileapex.membership.ChainStateField? =
        com.fileapex.membership.ChainStateField.parse(headers[com.fileapex.membership.ChainStateField.HEADER.lowercase()])

    fun membershipVersion(headers: Map<String, String>): Long? =
        headers[MEMBERSHIP_VERSION.lowercase()]?.toLongOrNull()
}
