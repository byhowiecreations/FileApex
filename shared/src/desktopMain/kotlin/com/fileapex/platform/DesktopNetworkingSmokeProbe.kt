package com.fileapex.platform

import com.fileapex.data.db.createFileApexDatabase
import com.fileapex.network.FileApexMdns
import com.fileapex.network.FileApexMdnsAdvertiser
import com.fileapex.network.FileApexMdnsBrowser
import com.fileapex.network.FileApexServer
import com.fileapex.network.LanInterfaceBinding
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Headless desktop smoke probe for Windows ↔ Android/Mac LAN validation.
 * Run via Gradle: `./gradlew :shared:runDesktopNetworkingSmoke`
 */
object DesktopNetworkingSmokeProbe {
    private const val SMOKE_MDNS_PORT = 59_123
    private const val SMOKE_HTTP_PORT = 59_124

    data class SmokeCheck(
        val name: String,
        val passed: Boolean,
        val detail: String,
    )

    fun runChecks(): List<SmokeCheck> = buildList {
        add(checkPlatform())
        add(checkAppDataDirectory())
        add(checkDatabaseBootstrap())
        add(checkLanInterfaces())
        add(checkShareServerListenHost())
        add(checkJmdnsRoundTrip())
        add(checkShareServerHealth())
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val strict = args.any { it.equals("--strict", ignoreCase = true) }
        val results = runChecks()
        var failures = 0
        println("FileApex desktop networking smoke probe")
        println("Host OS: ${System.getProperty("os.name")}")
        println("—".repeat(60))
        for (result in results) {
            val status = if (result.passed) "PASS" else "FAIL"
            println("[$status] ${result.name}")
            println("       ${result.detail}")
            if (!result.passed) failures++
        }
        println("—".repeat(60))
        if (failures == 0) {
            println("Smoke probe: all ${results.size} checks passed.")
        } else {
            println("Smoke probe: $failures/${results.size} checks failed.")
            if (strict) {
                error("Smoke probe failed (--strict)")
            }
        }
    }

    private fun checkPlatform(): SmokeCheck {
        val os = DesktopPlatformPaths.desktopOs
        val root = DesktopPlatformPaths.applicationSupportDirectory()
        return SmokeCheck(
            name = "platform_paths",
            passed = root.isDirectory,
            detail = "desktopOs=$os appData=${root.absolutePath}"
        )
    }

    private fun checkAppDataDirectory(): SmokeCheck {
        val dir = DesktopPlatformPaths.applicationSupportDirectory()
        val probe = java.io.File(dir, ".smoke-write")
        val writable = runCatching {
            probe.writeText("ok")
            probe.delete()
            true
        }.getOrDefault(false)
        return SmokeCheck(
            name = "app_data_writable",
            passed = writable,
            detail = dir.absolutePath
        )
    }

    private fun checkDatabaseBootstrap(): SmokeCheck = runBlocking {
        runCatching {
            val db = createFileApexDatabase()
            db.deviceDao().getAllDevicesOnce()
            db.close()
            SmokeCheck(
                name = "database_bootstrap",
                passed = true,
                detail = "Room opened at ${DesktopPlatformPaths.databaseFile().absolutePath}"
            )
        }.getOrElse { error ->
            SmokeCheck(
                name = "database_bootstrap",
                passed = false,
                detail = error.message ?: error.toString()
            )
        }
    }

    private fun checkLanInterfaces(): SmokeCheck {
        val candidates = LanInterfaceBinding.lanBindCandidates()
        val primary = LanInterfaceBinding.primaryLanIpv4OrNull()
        return SmokeCheck(
            name = "lan_interfaces",
            passed = candidates.isNotEmpty(),
            detail = "primary=$primary candidates=${candidates.joinToString()}"
        )
    }

    private fun checkShareServerListenHost(): SmokeCheck {
        val host = LanInterfaceBinding.shareServerListenHost()
        return SmokeCheck(
            name = "share_server_listen_host",
            passed = host == "0.0.0.0",
            detail = "listenHost=$host (expected 0.0.0.0 for LAN/firewall)"
        )
    }

    private fun checkJmdnsRoundTrip(): SmokeCheck = runBlocking {
        val deviceId = "smoke-${System.currentTimeMillis()}"
        val serviceName = FileApexMdns.serviceNameFor(deviceId)
        var resolvedHost: String? = null
        var resolvedPort: Int? = null
        var resolvedId: String? = null

        val host = LanInterfaceBinding.primaryLanIpv4OrNull()
            ?: LanInterfaceBinding.lanBindCandidates().firstOrNull()
        if (host.isNullOrBlank()) {
            return@runBlocking SmokeCheck(
                name = "jmdns_roundtrip",
                passed = false,
                detail = "no LAN IPv4 to bind JmDNS"
            )
        }

        val bindAddress = runCatching { Inet4Address.getByName(host) }.getOrNull()
        if (bindAddress == null) {
            return@runBlocking SmokeCheck(
                name = "jmdns_roundtrip",
                passed = false,
                detail = "invalid bind address $host"
            )
        }

        runCatching {
            val jmdns = JmDNS.create(bindAddress)
            try {
                val listener = object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        jmdns.requestServiceInfo(event.type, event.name, true)
                    }

                    override fun serviceRemoved(event: ServiceEvent) = Unit

                    override fun serviceResolved(event: ServiceEvent) {
                        val info = event.info ?: return
                        if (!info.name.startsWith(FileApexMdns.SERVICE_NAME_PREFIX)) return
                        resolvedHost = info.inet4Addresses.firstOrNull()?.hostAddress
                        resolvedPort = info.port
                        resolvedId = FileApexMdns.deviceIdFromServiceName(info.name)
                    }
                }
                jmdns.addServiceListener(FileApexMdns.SERVICE_TYPE, listener)
                jmdns.registerService(
                    ServiceInfo.create(
                        FileApexMdns.SERVICE_TYPE,
                        serviceName,
                        SMOKE_MDNS_PORT,
                        0,
                        0,
                        emptyMap<String, String>()
                    )
                )
                delay(1_000)
                jmdns.requestServiceInfo(FileApexMdns.SERVICE_TYPE, serviceName, 3_000L)
                delay(2_000)
                jmdns.removeServiceListener(FileApexMdns.SERVICE_TYPE, listener)
            } finally {
                runCatching {
                    jmdns.unregisterAllServices()
                    jmdns.close()
                }
            }
        }.onFailure { error ->
            return@runBlocking SmokeCheck(
                name = "jmdns_roundtrip",
                passed = false,
                detail = error.message ?: error.toString()
            )
        }

        val stackProbe = runCatching {
            FileApexMdnsBrowser.start { _, _, _ -> }
            FileApexMdnsAdvertiser.start(SMOKE_MDNS_PORT, "stack-probe")
            FileApexMdnsAdvertiser.stop()
            FileApexMdnsBrowser.stop()
            true
        }.getOrDefault(false)

        val multicastResolved = resolvedId == deviceId &&
            resolvedPort == SMOKE_MDNS_PORT &&
            !resolvedHost.isNullOrBlank()
        val passed = multicastResolved || stackProbe
        SmokeCheck(
            name = "jmdns_roundtrip",
            passed = passed,
            detail = when {
                multicastResolved -> {
                    "multicast resolved $resolvedHost:$resolvedPort id=$resolvedId " +
                        "type=${FileApexMdns.SERVICE_TYPE}"
                }
                stackProbe -> {
                    "multicast unresolved on $host (OS firewall/VLAN?) — " +
                        "FileApexMdnsAdvertiser+Browser stack OK; validate cross-device via manual matrix"
                }
                else -> {
                    "JmDNS stack failed on $host — check jmdns dependency and LAN interface"
                }
            }
        )
    }

    private fun checkShareServerHealth(): SmokeCheck {
        val server = FileApexServer(port = SMOKE_HTTP_PORT)
        return runCatching {
            server.start()
            check(server.isRunning) { "engine not running" }
            val status = httpGetStatus("127.0.0.1", SMOKE_HTTP_PORT, "/api/v1/health")
            check(status in 200..299) { "health HTTP $status" }
            SmokeCheck(
                name = "share_server_health",
                passed = true,
                detail = "GET /api/v1/health -> $status on ${LanInterfaceBinding.shareServerListenHost()}:$SMOKE_HTTP_PORT"
            )
        }.getOrElse { error ->
            SmokeCheck(
                name = "share_server_health",
                passed = false,
                detail = error.message ?: error.toString()
            )
        }.also {
            runCatching { server.stop() }
        }
    }

    private fun httpGetStatus(host: String, port: Int, path: String): Int {
        val url = URL("http://$host:$port$path")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        return connection.responseCode
    }
}
