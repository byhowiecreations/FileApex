package com.fileapex.platform

import com.fileapex.security.CommandRunner
import com.fileapex.util.cancellableCatching
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Windows asks once, the first time a program listens, and a dismissed prompt leaves an inbound Block rule that stops
 * every other device from reaching this one. This reads the rules for the running exe and can replace them with an Allow.
 * PowerShell objects are used because `netsh` prints localized text.
 */
class WindowsFirewall(
    private val runner: CommandRunner,
    private val exePath: () -> String?,
    private val isWindows: () -> Boolean
) {
    fun status(): FirewallInboundStatus {
        val exe = exePath()?.takeIf { isWindows() } ?: return FirewallInboundStatus.NotApplicable
        val result = cancellableCatching("WindowsFirewall status") {
            runner.run(powerShell(statusScript(exe)), null)
        }.getOrNull() ?: return FirewallInboundStatus.Unknown
        return if (result.first == 0) decide(result.second) else FirewallInboundStatus.Unknown
    }

    /** One UAC prompt. False when it was declined, the script failed or this is not Windows. */
    fun allow(): Boolean {
        val exe = exePath()?.takeIf { isWindows() } ?: return false
        val encoded = encode(allowScript(exe))
        val command = "try { \$p = Start-Process powershell -Verb RunAs -Wait -PassThru -WindowStyle Hidden " +
            "-ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','$encoded'; exit \$p.ExitCode } catch { exit 1 }"
        val result = cancellableCatching("WindowsFirewall allow") {
            runner.run(listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", command), null)
        }.getOrNull()
        return result?.first == 0
    }

    companion object {
        fun system(timeoutSeconds: Long) = WindowsFirewall(
            runner = TimedProcessRunner(timeoutSeconds),
            exePath = { ProcessHandle.current().info().command().orElse(null) },
            isWindows = { DesktopPlatformPaths.isWindows() }
        )

        fun decide(output: String): FirewallInboundStatus {
            val lines = output.lines().map { it.split('\t') }
            val enabledProfiles = lines.firstOrNull { it.firstOrNull() == "FIREWALL" }
                ?.getOrNull(1)?.trim()?.toIntOrNull() ?: return FirewallInboundStatus.Unknown
            if (enabledProfiles == 0) return FirewallInboundStatus.Allowed
            val inbound = lines.filter { it.size >= 5 && it[0] == "RULE" && it[1 + 2].trim().equals("Inbound", true) && it[2].trim().equals("True", true) }
            val actions = inbound.map { it[1].trim() }
            return when {
                actions.any { it.equals("Block", true) } -> FirewallInboundStatus.Blocked
                actions.any { it.equals("Allow", true) } -> FirewallInboundStatus.Allowed
                else -> FirewallInboundStatus.NoRule
            }
        }

        internal fun statusScript(exe: String): String = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}p = '${quoted(exe)}'
            "FIREWALL`t" + @(Get-NetFirewallProfile | Where-Object { ${'$'}_.Enabled -eq 'True' }).Count
            Get-NetFirewallApplicationFilter | Where-Object { ${'$'}_.Program -ieq ${'$'}p } | ForEach-Object {
              ${'$'}r = Get-NetFirewallRule -AssociatedNetFirewallApplicationFilter ${'$'}_
              "RULE`t${'$'}(${'$'}r.Action)`t${'$'}(${'$'}r.Enabled)`t${'$'}(${'$'}r.Direction)`t${'$'}(${'$'}r.Profile)"
            }
        """.trimIndent()

        internal fun allowScript(exe: String): String = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}p = '${quoted(exe)}'
            Get-NetFirewallApplicationFilter | Where-Object { ${'$'}_.Program -ieq ${'$'}p } | ForEach-Object { Get-NetFirewallRule -AssociatedNetFirewallApplicationFilter ${'$'}_ } | Where-Object { ${'$'}_.Direction -eq 'Inbound' -and ${'$'}_.Action -eq 'Block' } | Remove-NetFirewallRule
            New-NetFirewallRule -DisplayName 'FileApex' -Direction Inbound -Action Allow -Program ${'$'}p -Profile Private,Public | Out-Null
        """.trimIndent()

        private fun quoted(value: String) = value.replace("'", "''")

        private fun encode(script: String) = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

        private fun powerShell(script: String) = listOf("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encode(script))
    }
}

/** Waits up to [timeoutSeconds] (a UAC prompt can take a while), then kills the process. */
private class TimedProcessRunner(private val timeoutSeconds: Long) : CommandRunner {
    override fun run(args: List<String>, stdin: String?): Pair<Int, String> {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        process.outputStream.close()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("${args.first()} timed out after $timeoutSeconds s")
        }
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }
}
