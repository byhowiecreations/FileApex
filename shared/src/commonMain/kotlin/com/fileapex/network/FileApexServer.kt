package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.files.LocalFileRepository
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.pairing.ClusterSyncRequest
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.domain.peer.PeerNodeStateMapper
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.AppLocale
import com.fileapex.network.routes.registerBulletinRoutes
import com.fileapex.network.routes.registerClipboardRoutes
import com.fileapex.network.routes.registerDiagnosticRoutes
import com.fileapex.network.routes.registerFileRoutes
import com.fileapex.network.routes.registerIdentityRoutes
import com.fileapex.util.NetworkUtils
import com.fileapex.util.PathUtils
import com.fileapex.util.TimeUtils
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Persistent Ktor CIO host. Engine lifecycle is owned by the platform share controller
 * and is intentionally decoupled from individual request / pairing handler completion.
 */
class FileApexServer(
    private val port: Int,
    internal val identityProvider: () -> LocalIdentity = { loadLocalIdentity() },
    internal val onPairingRespond: suspend (PairedDeviceEntity) -> Unit = {},
    internal val onPairingRespondComplete: suspend (PairedDeviceEntity) -> Unit = {},
    internal val onClusterMerge: suspend (ClusterSyncRequest) -> Unit = {},
    internal val onListDevices: suspend () -> List<PairedDeviceEntity> = { emptyList() },
    internal val onLog: (String, Throwable?) -> Unit = { message, error ->
        if (error != null) {
            println("FileApexServer: $message :: ${error.message}")
            error.printStackTrace()
        } else {
            println("FileApexServer: $message")
        }
    }
) {
    private val engineLock = Any()
    private var serverEngine: EmbeddedServer<*, *>? = null
    private var lifecycleJob: Job = SupervisorJob()
    internal var serverScope: CoroutineScope = CoroutineScope(Dispatchers.IO + lifecycleJob)
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    internal val localFiles = LocalFileRepository()

    val isRunning: Boolean
        get() = synchronized(engineLock) { serverEngine != null }

    fun start() {
        synchronized(engineLock) {
            if (serverEngine != null) {
                onLog("start() ignored - engine already running on port $port", null)
                return
            }
            if (lifecycleJob.isCancelled) {
                lifecycleJob = SupervisorJob()
                serverScope = CoroutineScope(Dispatchers.IO + lifecycleJob)
            }

            val bindHost = LanInterfaceBinding.shareServerListenHost()
            val advertiseIp = LanInterfaceBinding.primaryLanIpv4OrNull()
            onLog(
                "Starting CIO engine on $bindHost:$port" +
                    (advertiseIp?.let { " (LAN $it)" }.orEmpty()),
                null
            )
            serverEngine = embeddedServer(CIO, port = port, host = bindHost) {
                install(StatusPages) {
                    exception<Throwable> { call, cause ->
                        onLog("Unhandled route exception", cause)
                        runCatching {
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                cause.message ?: "Internal server error"
                            )
                        }
                    }
                }

                intercept(ApplicationCallPipeline.Call) {
                    rememberInboundPeer(call)
                }

                routing {
                    registerIdentityRoutes(this@FileApexServer)
                    registerFileRoutes(this@FileApexServer)
                    registerClipboardRoutes(this@FileApexServer)
                    registerBulletinRoutes(this@FileApexServer)
                    registerDiagnosticRoutes(this@FileApexServer)
                }
            }.start(wait = false)

            serverScope.launch {
                onLog("CIO engine started and listening on port $port", null)
            }
        }
    }

    fun stop(gracePeriodMillis: Long = 1_000, timeoutMillis: Long = 2_000) {
        synchronized(engineLock) {
            onLog("Stopping CIO engine", null)
            runCatching {
                serverEngine?.stop(
                    gracePeriodMillis = gracePeriodMillis,
                    timeoutMillis = timeoutMillis
                )
            }.onFailure { error ->
                onLog("Error while stopping engine", error)
            }
            serverEngine = null
            lifecycleJob.cancel()
        }
    }

    internal suspend fun respondSelfPeerState(call: ApplicationCall) {
        val identity = identityProvider()
        val settings = FileApexServices.settings
        val state = PeerNodeStateMapper.selfState(
            identity = identity,
            pinRequired = settings.pinRequiredEnabled.value
        )
        call.respondText(
            text = json.encodeToString(PeerNodeState.serializer(), state),
            contentType = ContentType.Application.Json
        )
    }

    internal fun isPathAllowed(absolutePath: String): Boolean {
        val root = identityProvider().rootPath
        val normalized = PathUtils.normalize(absolutePath)
        if (normalized.isBlank() || normalized == "/" || normalized == "\\" || normalized == PathUtils.normalize(root)) return true
        return PathUtils.isWithinRoot(normalized, root)
    }

    internal fun providedPin(call: ApplicationCall): String {
        val fromQuery = call.request.queryParameters["pin"].orEmpty().trim()
        if (fromQuery.isNotEmpty()) return fromQuery
        return call.request.headers["X-FileApex-Pin"].orEmpty().trim()
    }

    internal fun providedPairingCode(call: ApplicationCall): String {
        val fromQuery = call.request.queryParameters["code"].orEmpty().trim()
        if (fromQuery.isNotEmpty()) return fromQuery
        return call.request.headers["X-FileApex-Pairing-Code"].orEmpty().trim()
    }

    /**
     * TCP source IP is the route we can actually reply on — overwrite advertised lastKnownIp.
     */
    private fun rememberInboundPeer(call: ApplicationCall) {
        val from = call.request.queryParameters["from"]?.trim().orEmpty().ifEmpty {
            call.request.headers["X-FileApex-Device-Id"]?.trim().orEmpty()
        }
        if (from.isEmpty()) return
        val ip = inboundPeerLanIpv4(call) ?: return
        val selfId = runCatching { identityProvider().deviceId }.getOrNull().orEmpty()
        if (from == selfId) return
        serverScope.launch {
            runCatching {
                val existing = FileApexServices.deviceRepository.getDevice(from) ?: return@runCatching
                FileApexServices.deviceRepository.touchPeerLastSeen(
                    deviceId = from,
                    ip = ip,
                    port = existing.port
                )
                FileApexServices.presenceMonitor.notifyPassiveReachability(from)
            }
        }
    }

    internal fun inboundPeerLanIpv4(call: ApplicationCall): String? {
        val raw = call.request.local.remoteAddress.trim()
            .ifBlank { call.request.local.remoteHost.trim() }
        val host = sanitizeInboundIpv4(raw) ?: return null
        return host.takeIf { NetworkUtils.isPrivateLanPeerHost(it) }
    }

    private fun sanitizeInboundIpv4(raw: String): String? {
        var value = raw.trim().removePrefix("/").substringBefore('%')
        if (value.startsWith("::ffff:", ignoreCase = true)) {
            value = value.substringAfter("::ffff:")
        }
        if (value.count { it == ':' } == 1 && value.contains('.')) {
            value = value.substringBefore(':')
        }
        return value.takeIf { it.isNotEmpty() }
    }

    /**
     * When PIN required is off, always accept.
     * When on, require a non-blank configured PIN that matches the peer-provided value.
     */
    internal fun isPeerPinAccepted(provided: String): Boolean {
        val settings = FileApexServices.settings
        if (!settings.pinRequiredEnabled.value) return true
        val expected = settings.devicePin.value
        return expected.isNotBlank() && provided == expected
    }

    /**
     * Reads an upload body without hanging when the sender closes early or stalls.
     * URLSession clients send Content-Length; FileApex/Ktor senders may use chunked EOF.
     * Partial files are kept at [targetPath] so a later request can resume from disk length.
     */
    internal suspend fun receiveUploadBytes(
        channel: ByteReadChannel,
        targetPath: String,
        startOffset: Long,
        expectedLength: Long?
    ): Long {
        var received = 0L
        var idleDeadlineMs = TimeUtils.now() + UPLOAD_IDLE_TIMEOUT_MS
        SocketFileStreamer.openAppender(targetPath, startOffset).use { raf ->
            val buffer = ByteArray(SocketFileStreamer.BUFFER_BYTES)
            while (expectedLength == null || received < expectedLength) {
                if (TimeUtils.now() >= idleDeadlineMs) break
                val remaining = expectedLength?.minus(received)
                val want = if (remaining == null) {
                    buffer.size
                } else {
                    minOf(buffer.size.toLong(), remaining).toInt().coerceAtLeast(1)
                }
                val read = channel.readAvailable(buffer, 0, want)
                when {
                    read > 0 -> {
                        raf.write(buffer, 0, read)
                        received += read.toLong()
                        idleDeadlineMs = TimeUtils.now() + UPLOAD_IDLE_TIMEOUT_MS
                    }
                    channel.isClosedForRead -> break
                    expectedLength != null && received >= expectedLength -> break
                    !channel.awaitContent() -> break
                }
            }
        }
        return received
    }

    companion object {
        private const val UPLOAD_IDLE_TIMEOUT_MS = 60_000L
        internal fun webShareHtml(): String {
            val i18n = buildJsonObject {
                put("title", AppI18n.t("web_share_title"))
                put("selectDevice", AppI18n.t("web_share_select_device"))
                put("textLabel", AppI18n.t("web_share_text_label"))
                put("placeholder", AppI18n.t("web_share_placeholder"))
                put("send", AppI18n.t("web_share_send"))
                put("loading", AppI18n.t("web_share_loading"))
                put("empty", AppI18n.t("no_paired_devices_found"))
                put("errorLoading", AppI18n.t("web_share_error_loading"))
                put("selectDest", AppI18n.t("web_share_select_dest"))
                put("enterText", AppI18n.t("web_share_enter_text"))
                put("sending", AppI18n.t("web_share_sending"))
                put("receivedBy", AppI18n.t("web_share_received_by", "{name}"))
                put("failed", AppI18n.t("web_share_failed"))
                put("errorSending", AppI18n.t("web_share_error_sending", "{err}"))
            }
            val lang = when (AppI18n.locale) {
                AppLocale.ES -> "es"
                AppLocale.ZH_HANS -> "zh-CN"
                else -> "en"
            }
            return """
            <!DOCTYPE html>
            <html lang="$lang">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title></title>
            <style>
              body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 20px; display: flex; justify-content: center; align-items: center; min-height: 100vh; }
              .card { background: #1e293b; border-radius: 16px; padding: 24px; max-width: 480px; width: 100%; box-shadow: 0 10px 25px rgba(0,0,0,0.5); border: 1px solid #334155; }
              h1 { font-size: 1.5rem; margin-top: 0; color: #38bdf8; text-align: center; }
              label { font-size: 0.9rem; color: #94a3b8; margin-top: 16px; display: block; }
              select, textarea, button { width: 100%; border-radius: 8px; border: 1px solid #475569; padding: 12px; margin-top: 6px; box-sizing: border-box; font-size: 1rem; background: #0f172a; color: #f8fafc; }
              textarea { height: 120px; resize: vertical; }
              button { background: #0284c7; border: none; font-weight: 600; cursor: pointer; margin-top: 20px; transition: background 0.2s; }
              button:hover { background: #0369a1; }
              .snackbar { visibility: hidden; min-width: 250px; background-color: #334155; color: #fff; text-align: center; border-radius: 8px; padding: 14px; position: fixed; z-index: 100; left: 50%; bottom: 30px; transform: translateX(-50%); font-size: 1rem; border: 1px solid #0284c7; box-shadow: 0 4px 12px rgba(0,0,0,0.3); }
              .snackbar.show { visibility: visible; animation: fadein 0.3s, fadeout 0.3s 2.7s; }
              @keyframes fadein { from { bottom: 0; opacity: 0; } to { bottom: 30px; opacity: 1; } }
              @keyframes fadeout { from { bottom: 30px; opacity: 1; } to { bottom: 0; opacity: 0; } }
            </style>
            </head>
            <body>
            <div class="card">
              <h1 id="pageTitle"></h1>
              <label id="deviceLabel" for="deviceSelect"></label>
              <select id="deviceSelect"></select>
              <label id="textLabel" for="shareText"></label>
              <textarea id="shareText"></textarea>
              <button id="sendBtn" onclick="sendClipboard()"></button>
            </div>
            <div id="snackbar" class="snackbar"></div>
            <script>
              const I18N = $i18n;
              document.title = I18N.title;
              document.getElementById("pageTitle").innerText = I18N.title;
              document.getElementById("deviceLabel").innerText = I18N.selectDevice;
              document.getElementById("textLabel").innerText = I18N.textLabel;
              document.getElementById("shareText").placeholder = I18N.placeholder;
              document.getElementById("sendBtn").innerText = I18N.send;
              document.getElementById("deviceSelect").innerHTML = '<option value="">' + I18N.loading + '</option>';
              let snackbarTimer;
              function showSnackbar(msg) {
                const sb = document.getElementById("snackbar");
                sb.innerText = msg;
                sb.className = "snackbar show";
                clearTimeout(snackbarTimer);
                snackbarTimer = setTimeout(() => { sb.className = "snackbar"; }, 3000);
              }
              async function loadDevices() {
                try {
                  const res = await fetch('/api/v1/devices');
                  const devices = await res.json();
                  const select = document.getElementById('deviceSelect');
                  select.innerHTML = '';
                  if (!devices || devices.length === 0) {
                    select.innerHTML = '<option value="">' + I18N.empty + '</option>';
                    return;
                  }
                  devices.forEach(d => {
                    const opt = document.createElement('option');
                    opt.value = d.deviceId;
                    opt.innerText = d.deviceName + ' (' + d.ipAddress + ')';
                    select.appendChild(opt);
                  });
                } catch (e) {
                  document.getElementById('deviceSelect').innerHTML = '<option value="">' + I18N.errorLoading + '</option>';
                }
              }
              async function sendClipboard() {
                const deviceId = document.getElementById('deviceSelect').value;
                const text = document.getElementById('shareText').value;
                if (!deviceId) { alert(I18N.selectDest); return; }
                if (!text.trim()) { alert(I18N.enterText); return; }
                showSnackbar(I18N.sending);
                try {
                  const res = await fetch('/api/v1/web/send-clipboard', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ targetDeviceId: deviceId, text: text })
                  });
                  const data = await res.json();
                  if (res.ok && data.status === 'ok') {
                    showSnackbar(I18N.receivedBy.replace('{name}', data.recipientDeviceName));
                    document.getElementById('shareText').value = '';
                  } else {
                    showSnackbar(data.message || I18N.failed);
                  }
                } catch (e) {
                  showSnackbar(I18N.errorSending.replace('{err}', e.message));
                }
              }
              loadDevices();
            </script>
            </body>
            </html>
            """.trimIndent()
        }
    }
}
