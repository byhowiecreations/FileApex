package com.fileapex.iroh

object IrohAddressKey {
    fun of(address: String): String {
        val parts = address.split("|")
        if (parts.size != 3) return address
        val direct = parts[2].split(",").filter { it.isNotBlank() }.sorted().joinToString(",")
        return "${parts[0]}|${parts[1]}|$direct"
    }

    /** What differs between two addresses, by kind only, for the debug log. Never contains an address. */
    fun describeChange(before: String, after: String): String {
        val b = before.split("|")
        val a = after.split("|")
        if (b.size != 3 || a.size != 3) return "unparsed"
        val relay = when {
            b[1] == a[1] -> if (a[1].isBlank()) "relay none" else "relay same"
            else -> "relay changed"
        }
        val old = b[2].split(",").filter { it.isNotBlank() }.toSet()
        val new = a[2].split(",").filter { it.isNotBlank() }.toSet()
        val added = (new - old).map(::kindOf).sorted()
        val removed = (old - new).map(::kindOf).sorted()
        if (added.isEmpty() && removed.isEmpty()) return "$relay, direct none"
        val parts = buildList {
            if (added.isNotEmpty()) add("added ${added.joinToString(", ")}")
            if (removed.isNotEmpty()) add("removed ${removed.joinToString(", ")}")
        }
        return "$relay, direct +${added.size} -${removed.size} (${parts.joinToString("; ")})"
    }

    private fun kindOf(socketAddress: String): String {
        val host = socketAddress.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
        if (host.contains(":")) {
            val lower = host.lowercase()
            return when {
                lower.startsWith("fe80") -> "v6-link-local"
                lower.startsWith("fc") || lower.startsWith("fd") -> "v6-ula"
                else -> "v6-global"
            }
        }
        val octets = host.split(".").mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return "other"
        val (a, b) = octets[0] to octets[1]
        val private = a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)
        return if (private) "v4-private" else "v4-public"
    }
}
