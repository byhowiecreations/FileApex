package com.fileapex.iroh

data class IrohRelayConfig(val url: String, val token: String)

enum class IrohRelayUrlProblem { Empty, NotHttps, IpAddress, NoHostName, Malformed }

sealed interface IrohRelayUrlCheck {
    data class Valid(val url: String) : IrohRelayUrlCheck
    data class Invalid(val problem: IrohRelayUrlProblem) : IrohRelayUrlCheck
}

/**
 * The relay needs a certificate, and certificates are issued to host names, so only
 * `https://name.example.com` (optionally with a port) is accepted. A bare IP address is refused.
 */
object IrohRelayUrl {
    private val label = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun check(input: String): IrohRelayUrlCheck {
        val text = input.trim()
        if (text.isEmpty()) return invalid(IrohRelayUrlProblem.Empty)
        if (text.any { it.isWhitespace() || it.isISOControl() }) return invalid(IrohRelayUrlProblem.Malformed)
        if (!text.startsWith("https://", ignoreCase = true)) return invalid(IrohRelayUrlProblem.NotHttps)
        val rest = text.substring("https://".length).trimEnd('/')
        if (rest.isEmpty() || rest.any { it == '/' || it == '?' || it == '#' || it == '@' }) {
            return invalid(IrohRelayUrlProblem.Malformed)
        }
        if (rest.startsWith("[")) return invalid(IrohRelayUrlProblem.IpAddress)
        val host = rest.substringBefore(':')
        val port = rest.substringAfter(':', "")
        if (rest.contains(':') && (port.toIntOrNull()?.let { it in 1..65535 } != true)) {
            return invalid(IrohRelayUrlProblem.Malformed)
        }
        if (ipv4.matches(host)) return invalid(IrohRelayUrlProblem.IpAddress)
        val labels = host.split('.')
        if (labels.size < 2 || host.equals("localhost", ignoreCase = true)) {
            return invalid(IrohRelayUrlProblem.NoHostName)
        }
        if (labels.any { !label.matches(it) }) return invalid(IrohRelayUrlProblem.Malformed)
        // The last label of a real host name is not numeric (a dotted number is an address, not a name).
        if (labels.last().all { it.isDigit() }) return invalid(IrohRelayUrlProblem.IpAddress)
        return IrohRelayUrlCheck.Valid("https://" + host.lowercase() + (if (port.isNotEmpty()) ":$port" else ""))
    }

    private fun invalid(problem: IrohRelayUrlProblem) = IrohRelayUrlCheck.Invalid(problem)
}
