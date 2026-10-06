package com.fileapex.tailscale

import com.fileapex.di.FileApexServices
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object TailscaleNodeRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val applyLock = Mutex()
    private val engine: TailscaleUserspaceEngine by lazy { embeddedTsnetEngine() }
    private val _state = MutableStateFlow(TailscaleUiState())
    val state: StateFlow<TailscaleUiState> = _state.asStateFlow()

    @Volatile
    private var attached = false

    @Volatile
    private var forceReset = false

    @Volatile
    private var signingOut = false

    @Volatile
    private var showLoginPage = false

    @Volatile
    private var loginPageShown = false

    private var loginWatch: Job? = null

    /** Fetches the login page address before Sign in, so the default browser can open it immediately. */
    fun prepareSignIn() {
        scope.launch(Dispatchers.IO) {
            val phase = _state.value.phase
            if (phase == TailscalePhase.Starting ||
                phase == TailscalePhase.Up ||
                phase == TailscalePhase.NeedsLogin ||
                phase == TailscalePhase.KeyExpired
            ) {
                return@launch
            }
            val settings = FileApexServices.settings
            val authKey = settings.tailscaleAuthKey.value.trim()
            if (tailscaleReadyWithoutLogin(authKey, readTailscaleNodeState(tailscaleNodeStateFile()))) {
                return@launch
            }
            settings.setTailscaleEnabled(true)
            _state.value = reconcileTailscale(enabled = true, authKey = authKey, engine = null)
            wake.trySend(Unit)
        }
    }

    fun attach() {
        if (attached) return
        attached = true
        scope.launch(Dispatchers.IO) {
            while (true) {
                wake.receive()
                try {
                    apply()
                } catch (error: CancellationException) {
                    throw error
                }
            }
        }
        wake.trySend(Unit)
    }

    /** The browser opened fileapex://auth-callback. Refresh the node without opening the page again. */
    fun onBrowserLoginReturned() {
        scope.launch(Dispatchers.IO) {
            showLoginPage = false
            val snapshot = engine.poll()
            if (snapshot.phase == TailscalePhase.Down || snapshot.phase == TailscalePhase.Failed) return@launch
            val settings = FileApexServices.settings
            val authKey = settings.tailscaleAuthKey.value.trim()
            val url = tailscaleLoginUrl(snapshot.authUrl)
            val next = tailscaleAfterBringUp(authKey, snapshot).copy(authUrl = url.orEmpty())
            if (next.phase == TailscalePhase.Up && isTailscaleAuthKey(authKey)) {
                settings.setTailscaleAuthKeyFingerprint(tailscaleAuthKeyFingerprint(authKey))
            }
            _state.value = next
        }
    }

    fun signIn() {
        scope.launch(Dispatchers.IO) {
            val current = _state.value
            val phase = current.phase
            if (phase == TailscalePhase.Up) {
                showLoginPage = false
                return@launch
            }
            val settings = FileApexServices.settings
            val authKey = settings.tailscaleAuthKey.value.trim()
            showLoginPage = true
            val url = tailscaleLoginUrl(current.authUrl)
                ?: tailscaleLoginUrl(runCatching { engine.poll().authUrl }.getOrDefault(""))
            val waiting = phase == TailscalePhase.NeedsLogin ||
                phase == TailscalePhase.KeyExpired ||
                phase == TailscalePhase.Starting
            if (url != null && waiting) {
                if (!settings.tailscaleEnabled.value) settings.setTailscaleEnabled(true)
                openLoginPage(url)
                return@launch
            }
            if (!settings.tailscaleEnabled.value) settings.setTailscaleEnabled(true)
            if (waiting) return@launch
            _state.value = reconcileTailscale(enabled = true, authKey = authKey, engine = null)
            wake.trySend(Unit)
        }
    }

    fun setEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            FileApexServices.settings.setTailscaleEnabled(enabled)
            wake.trySend(Unit)
        }
    }

    fun saveConfiguration(authKey: String) {
        scope.launch(Dispatchers.IO) {
            val trimmed = authKey.trim()
            val settings = FileApexServices.settings
            if (!isTailscaleAuthKey(trimmed)) {
                _state.value = _state.value.copy(saveDetail = "invalid_auth_key")
                return@launch
            }
            val previous = settings.tailscaleAuthKey.value.trim()
            settings.setTailscaleAuthKey(trimmed)
            settings.setTailscaleEnabled(false)
            if (previous.isNotEmpty() && previous != trimmed) {
                forceReset = true
            }
            wake.trySend(Unit)
        }
    }

    fun reauthenticate() {
        forceReset = true
        wake.trySend(Unit)
    }

    /** Expires the node on Tailscale, then deletes the local node store and saved key. */
    fun signOut() {
        signingOut = true
        loginWatch?.cancel()
        loginWatch = null
        scope.launch(Dispatchers.IO) {
            try {
                applyLock.withLock { performSignOut() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                println("Tailscale sign-out failed")
                _state.value = _state.value.copy(phase = TailscalePhase.Failed, detail = "sign_out_failed")
            } finally {
                signingOut = false
            }
        }
    }

    fun activeTailnetConnections(): Int {
        val phase = _state.value.phase
        if (phase == TailscalePhase.Off ||
            phase == TailscalePhase.NeedsKey ||
            phase == TailscalePhase.Down ||
            phase == TailscalePhase.Failed
        ) {
            return 0
        }
        return runCatching { engine.activeConnections() }.getOrDefault(0)
    }

    /**
     * Null when the node is Up but the peer list could not be read.
     * Empty when the node is not Up, without a native call.
     */
    fun observedTailnetPeers(): List<TailscaleObservedPeer>? {
        if (_state.value.phase != TailscalePhase.Up) return emptyList()
        val raw = runCatching { engine.tailnetPeersJson() }.getOrNull() ?: return null
        return parseTailnetPeers(raw)
    }

    private suspend fun performSignOut() {
        showLoginPage = false
        loginPageShown = false
        val settings = FileApexServices.settings
        val stateSignedIn = tailscaleStateIsSignedIn(
            readTailscaleNodeState(tailscaleNodeStateFile()).orEmpty()
        )
        val phase = _state.value.phase
        val registered = stateSignedIn ||
            phase == TailscalePhase.Up ||
            phase == TailscalePhase.NeedsLogin ||
            phase == TailscalePhase.KeyExpired
        _state.value = _state.value.copy(detail = "signing_out")
        if (registered && !expireNodeOnControl()) {
            _state.value = _state.value.copy(phase = TailscalePhase.Failed, detail = "sign_out_failed")
            return
        }
        settings.setTailscaleEnabled(false)
        engine.stop()
        engine.clearState()
        settings.setTailscaleAuthKey("")
        settings.setTailscaleAuthKeyFingerprint("")
        forceReset = false
        _state.value = TailscaleUiState()
    }

    private suspend fun expireNodeOnControl(): Boolean {
        val phase = runCatching { engine.poll().phase }.getOrDefault(TailscalePhase.Down)
        val running = phase == TailscalePhase.Up ||
            phase == TailscalePhase.NeedsLogin ||
            phase == TailscalePhase.KeyExpired ||
            phase == TailscalePhase.Starting
        var startedForLogout = false
        if (!running) {
            startedForLogout = true
            holdTailscaleProcess()
            val snapshot = try {
                engine.start("")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return false
            }
            if (snapshot.phase == TailscalePhase.Failed || snapshot.phase == TailscalePhase.Down) {
                engine.stop()
                return false
            }
        }
        val removed = engine.logout() == null
        if (!removed && startedForLogout) {
            engine.stop()
        }
        return removed
    }

    private suspend fun apply() {
        applyLock.withLock {
            if (signingOut) return@withLock
            loginWatch?.cancel()
            loginWatch = null
            val settings = FileApexServices.settings
            val enabled = settings.tailscaleEnabled.value
            val authKey = settings.tailscaleAuthKey.value.trim()
            val reset = tailscaleEnrollmentReset(
                savedFingerprint = settings.tailscaleAuthKeyFingerprint.value,
                authKey = authKey,
                force = forceReset
            )
            if (reset) forceReset = false
            engine.stop()
            if (reset) {
                engine.clearState()
                settings.setTailscaleAuthKeyFingerprint("")
            }
            if (!enabled) {
                _state.value = reconcileTailscale(enabled = false, authKey = authKey, engine = null)
                return
            }
            val alreadyConnected = !reset && tailscaleReadyWithoutLogin(
                authKey,
                readTailscaleNodeState(tailscaleNodeStateFile())
            )
            holdTailscaleProcess()
            loginPageShown = false
            _state.value = reconcileTailscale(enabled, authKey, engine = null).let { starting ->
                if (alreadyConnected) starting.copy(detail = "already_connected") else starting
            }
            val snapshot = try {
                engine.start(if (isTailscaleAuthKey(authKey)) authKey else "")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                tailscaleStartFailure(error.message ?: "start_failed")
            }
            val stillRequested = settings.tailscaleEnabled.value &&
                settings.tailscaleAuthKey.value.trim() == authKey
            if (!stillRequested) {
                engine.stop()
                _state.value = reconcileTailscale(
                    enabled = settings.tailscaleEnabled.value,
                    authKey = settings.tailscaleAuthKey.value,
                    engine = null
                )
                return
            }
            val loginUrl = tailscaleLoginUrl(snapshot.authUrl)
            var next = tailscaleAfterBringUp(authKey, snapshot).copy(authUrl = loginUrl.orEmpty())
            if (alreadyConnected && next.phase == TailscalePhase.Starting) {
                next = next.copy(detail = "already_connected")
            }
            if (!next.enabled) {
                settings.setTailscaleEnabled(false)
                engine.stop()
            } else if (snapshot.phase == TailscalePhase.Up) {
                if (isTailscaleAuthKey(authKey)) {
                    settings.setTailscaleAuthKeyFingerprint(tailscaleAuthKeyFingerprint(authKey))
                }
                holdTailscaleProcess()
            } else if (snapshot.phase == TailscalePhase.NeedsLogin ||
                snapshot.phase == TailscalePhase.KeyExpired
            ) {
                holdTailscaleProcess()
                if (loginUrl != null && showLoginPage) openLoginPage(loginUrl)
                watchUntilRunning(authKey)
            }
            _state.value = next
            if (next.phase == TailscalePhase.Up && showLoginPage) {
                showLoginPage = false
                if (loginPageShown) returnToAppAfterTailscaleLogin()
            }
        }
    }

    private fun openLoginPage(url: String) {
        if (loginPageShown) return
        loginPageShown = true
        openTailscaleLoginInBrowser(url)
    }

    private fun watchUntilRunning(authKey: String) {
        loginWatch?.cancel()
        loginWatch = scope.launch(Dispatchers.IO) {
            while (isActive && !signingOut) {
                delay(250)
                if (signingOut || !isActive || !FileApexServices.settings.tailscaleEnabled.value) return@launch
                val snapshot = engine.poll()
                if (signingOut || !isActive || !FileApexServices.settings.tailscaleEnabled.value) return@launch
                val url = tailscaleLoginUrl(snapshot.authUrl)
                if (url != null &&
                    (snapshot.phase == TailscalePhase.NeedsLogin || snapshot.phase == TailscalePhase.KeyExpired)
                ) {
                    if (showLoginPage) openLoginPage(url)
                }
                val next = tailscaleAfterBringUp(authKey, snapshot).copy(authUrl = url.orEmpty())
                if (!next.enabled) {
                    FileApexServices.settings.setTailscaleEnabled(false)
                    engine.stop()
                    _state.value = next
                    return@launch
                }
                if (next.phase == TailscalePhase.Up && isTailscaleAuthKey(authKey)) {
                    FileApexServices.settings.setTailscaleAuthKeyFingerprint(tailscaleAuthKeyFingerprint(authKey))
                }
                _state.value = next
                if (next.phase == TailscalePhase.Up && showLoginPage) {
                    showLoginPage = false
                    if (loginPageShown) returnToAppAfterTailscaleLogin()
                }
                if (next.phase == TailscalePhase.Up ||
                    next.phase == TailscalePhase.Failed ||
                    next.phase == TailscalePhase.Down
                ) {
                    return@launch
                }
            }
        }
    }
}
