package com.vauth.foxyvpn.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.vauth.foxyvpn.FoxyVpnApp
import com.vauth.foxyvpn.MainActivity
import com.vauth.foxyvpn.data.AppLogger
import com.vauth.foxyvpn.data.ControlPlaneHttp
import com.vauth.foxyvpn.data.FxaAuthRepository
import com.vauth.foxyvpn.data.GUARDIAN_ENDPOINT_DEFAULT
import com.vauth.foxyvpn.data.GuardianClient
import com.vauth.foxyvpn.data.ProxyPass
import com.vauth.foxyvpn.data.ProxyStateStore
import com.vauth.foxyvpn.data.QuotaExceededError
import com.vauth.foxyvpn.data.RECOMMENDED_COUNTRY_CODE
import com.vauth.foxyvpn.data.ServerListClient
import com.vauth.foxyvpn.data.SettingsStore
import com.vauth.foxyvpn.data.TokenInvalidError
import com.vauth.foxyvpn.data.TokenStore
import com.vauth.foxyvpn.data.formatBytesPerSecond
import com.vauth.foxyvpn.data.model.ConnectionState
import com.vauth.foxyvpn.data.model.ProxyCandidate
import com.vauth.foxyvpn.data.model.RuntimeAuth
import com.vauth.foxyvpn.vpn.socks.LocalSocks5Server
import com.vauth.foxyvpn.vpn.tun.HevSocks5Tunnel
import com.vauth.foxyvpn.vpn.tun.HevSocks5TunnelConfig
import com.vauth.foxyvpn.vpn.upstream.EdgeAddressResolver
import com.vauth.foxyvpn.vpn.upstream.H2UpstreamSession
import com.vauth.foxyvpn.vpn.upstream.UpstreamProxyConfig
import com.vauth.foxyvpn.vpn.upstream.UpstreamSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

private const val TAG = "FoxyVpnService"
private const val CONNECT_TIMEOUT_MS = 20_000L
private const val SERVER_LIST_FETCH_TIMEOUT_MS = 15_000L

private const val UPSTREAM_WATCHDOG_INTERVAL_MS = 2_000L

private const val RECONNECT_FAILURES_BEFORE_WARNING = 5
private const val RECONNECT_BACKOFF_BASE_MS = 3_000L
private const val RECONNECT_BACKOFF_CAP_MS = 60_000L

private const val RECONNECT_FAILURES_BEFORE_EDGE_ROTATION = 2

private const val MAX_ALTERNATE_EDGES = 3

private const val UNHEALTHY_REDIAL_COOLDOWN_MS = 30_000L

private const val PROXY_PASS_RENEWAL_FRACTION = 0.5
private const val PROXY_PASS_RENEWAL_SAFETY_MARGIN_MS = 30_000L

private const val PROXY_PASS_RENEWAL_FLOOR_MS = 15_000L

private const val PROXY_PASS_RENEWAL_CEILING_MS = 30 * 60_000L

private const val PROXY_PASS_RENEWAL_FALLBACK_MS = 4 * 60_000L

private const val PROXY_PASS_RENEWAL_RETRY_MS = 30_000L

private const val POST_TUN_SETTLE_MS = 250L

private const val INITIAL_DIAL_SETTLE_MS = 600L

private const val INITIAL_DIAL_MAX_ATTEMPTS = MAX_ALTERNATE_EDGES + 1
private const val INITIAL_DIAL_BACKOFF_BASE_MS = 2_000L
private const val INITIAL_DIAL_BACKOFF_CAP_MS = 8_000L

private const val SPEED_UPDATE_INTERVAL_MS = 2_000L

private const val TUN_ADDRESS_IPV6 = "fd00:1:fd00:1:fd00:1:fd00:1"
private const val TUN_PREFIX_LENGTH_IPV6 = 128

private const val WAKE_LOCK_TAG = "com.vauth.foxyvpn:vpn"
private const val WIFI_LOCK_TAG = "com.vauth.foxyvpn:vpn-wifi"

private fun fullJitterBackoffMs(attempt: Int, baseMs: Long, capMs: Long): Long {
    val exponential = baseMs * (1L shl attempt.coerceIn(0, 10))
    val upperBound = exponential.coerceAtMost(capMs)
    return (upperBound.toDouble() * Math.random()).toLong().coerceAtLeast(baseMs / 4)
}

private fun proxyPassRenewalDelayMs(expiresAtEpochSeconds: Long?): Long {
    if (expiresAtEpochSeconds == null) return PROXY_PASS_RENEWAL_FALLBACK_MS
    val remainingMs = expiresAtEpochSeconds * 1_000L - System.currentTimeMillis()
    if (remainingMs <= 0L) return PROXY_PASS_RENEWAL_FLOOR_MS
    val atHalfLife = (remainingMs * PROXY_PASS_RENEWAL_FRACTION).toLong()
    val beforeExpiry = remainingMs - PROXY_PASS_RENEWAL_SAFETY_MARGIN_MS
    return minOf(atHalfLife, beforeExpiry)
        .coerceAtMost(PROXY_PASS_RENEWAL_CEILING_MS)
        .coerceAtLeast(PROXY_PASS_RENEWAL_FLOOR_MS)
}

class FoxyVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val opMutex = Mutex()

    private var tunFd: ParcelFileDescriptor? = null
    private var socksServer: LocalSocks5Server? = null
    private var upstreamSession: UpstreamSession? = null
    private var connectJob: Job? = null
    private var watchdogJob: Job? = null
    private var speedJob: Job? = null

    private var tokenRenewalJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var tunnelInterfaceActive = false

    @Volatile private var boundNetwork: Network? = null

    @Volatile private var reportedUnderlying: Network? = null

    @Volatile private var lastUnhealthyRedialAt = 0L

    @Volatile private var statusLabel: String = "Connecting\u2026"

    @Volatile private var foregroundActive = false

    @Volatile private var lastNotificationText: String? = null

    private class SessionResources(
        val tunFd: ParcelFileDescriptor?,
        val socksServer: LocalSocks5Server?,
        val upstreamSession: UpstreamSession?,
    ) {
        val isEmpty: Boolean get() = tunFd == null && socksServer == null && upstreamSession == null
    }

    @Volatile
    private var connectionGeneration = 0

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            reportUnderlyingNetwork(network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            reportUnderlyingNetwork(network, capabilities)
        }

        override fun onLost(network: Network) {
            if (boundNetwork != network) return
            boundNetwork = null
            reportedUnderlying = null

            if (tunnelInterfaceActive) {
                runCatching { setUnderlyingNetworks(null) }
            }
        }
    }

    private fun reportUnderlyingNetwork(
        network: Network,
        capabilities: NetworkCapabilities? = null,
    ) {
        val caps = capabilities
            ?: getSystemService(ConnectivityManager::class.java)?.getNetworkCapabilities(network)

        if (caps == null) return

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

        if (tunnelInterfaceActive && reportedUnderlying != network) {
            reportedUnderlying = network
            runCatching { setUnderlyingNetworks(arrayOf(network)) }
                .onFailure {

                    reportedUnderlying = null
                    AppLogger.w(TAG, "failed to report the underlying network to the platform", it)
                }
        }

        val previous = boundNetwork
        if (previous == network) return

        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
        boundNetwork = network
        if (previous == null) return

        if (_state.value != ConnectionState.CONNECTED) return
        val session = upstreamSession ?: return

        AppLogger.i(
            TAG,
            "underlying network changed; closing the upstream session bound to the old one so it is redialed now " +
                "(its socket would otherwise stay ESTABLISHED and look healthy for minutes)",
        )
        runCatching { session.close() }
    }

    private fun onUpstreamSessionUnhealthy() {
        if (_state.value != ConnectionState.CONNECTED) return
        val session = upstreamSession ?: return
        val now = System.currentTimeMillis()
        val sinceLast = now - lastUnhealthyRedialAt
        if (lastUnhealthyRedialAt != 0L && sinceLast < UNHEALTHY_REDIAL_COOLDOWN_MS) {
            AppLogger.d(
                TAG,
                "ignoring an unhealthy-session verdict ${sinceLast}ms after the last rebuild " +
                    "(cooldown ${UNHEALTHY_REDIAL_COOLDOWN_MS}ms)",
            )
            return
        }
        lastUnhealthyRedialAt = now
        AppLogger.w(
            TAG,
            "the local proxy reports the upstream session is failing as a whole rather than for one destination; " +
                "closing it so the watchdog redials",
        )
        runCatching { session.close() }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
        }.onFailure { AppLogger.w(TAG, "could not register a network callback; handovers will be slower to detect", it) }
    }

    override fun onDestroy() {
        ControlPlaneHttp.socketProtector = null
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        }
        connectJob?.cancel()
        connectJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        connectionGeneration++
        releaseResources(detachResources(), stopNativeTunnel = true)
        releaseWakeLocks()
        foregroundActive = false
        lastNotificationText = null
        boundNetwork = null
        reportedUnderlying = null
        _state.value = ConnectionState.DISCONNECTED
        super.onDestroy()
    }

    private fun acquireWakeLocks() {
        if (wakeLock == null) {
            runCatching {
                val power = getSystemService(PowerManager::class.java)
                    ?: error("PowerManager unavailable")
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {

                    setReferenceCounted(false)
                    acquire()
                }
                AppLogger.i(TAG, "acquired a CPU wake lock for the session")
            }.onFailure {
                AppLogger.w(TAG, "could not acquire a CPU wake lock; the tunnel may stall while the screen is off", it)
            }
        }
        if (wifiLock == null) {
            runCatching {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: error("WifiManager unavailable")
                @Suppress("DEPRECATION")
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure { AppLogger.d(TAG, "Wi-Fi high-performance lock unavailable: ${it.message}") }
        }
    }

    private fun releaseWakeLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { AppLogger.d(TAG, "error releasing the CPU wake lock: ${it.message}") }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            .onFailure { AppLogger.d(TAG, "error releasing the Wi-Fi lock: ${it.message}") }
        wifiLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {

                if (_state.value != ConnectionState.DISCONNECTED) {
                    AppLogger.d(TAG, "ignoring duplicate connect request while ${_state.value}")

                    enterForeground(statusLabel)
                    return START_NOT_STICKY
                }
                _state.value = ConnectionState.CONNECTING
                _lastError.value = null
                statusLabel = "Connecting\u2026"
                enterForeground(statusLabel)

                acquireWakeLocks()
                connectJob = scope.launch { opMutex.withLock { connect() } }
            }
            ACTION_DISCONNECT -> requestDisconnect("requested by the user")
        }
        return START_NOT_STICKY
    }

    private fun requestDisconnect(reason: String) {
        val idle = _state.value == ConnectionState.DISCONNECTED &&
            connectJob == null &&
            watchdogJob == null
        if (idle) {
            releaseWakeLocks()
            stopSelf()
            return
        }
        AppLogger.i(TAG, "disconnect: $reason")

        val endedGeneration = ++connectionGeneration
        _state.value = ConnectionState.DISCONNECTED
        connectJob?.cancel()
        connectJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        exitForeground()

        releaseWakeLocks()

        val doomed = detachResources()

        scope.launch {
            opMutex.withLock {

                val stillOurs = connectionGeneration == endedGeneration
                releaseResources(doomed, stopNativeTunnel = stillOurs)

                if (stillOurs && _state.value == ConnectionState.DISCONNECTED) {
                    stopSelf()
                }
            }
        }
    }

    private fun ensureGenerationCurrent(myGeneration: Int) {
        if (myGeneration != connectionGeneration) {
            throw CancellationException("connect was superseded by a newer request")
        }
    }

    private fun hasUsableNetwork(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true

        val active = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(active) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isFatalUpstreamError(error: Throwable): Boolean =
        error is TokenInvalidError || error is QuotaExceededError

    private fun startProxyPassRenewal(
        myGeneration: Int,
        initialExpiry: Long?,
        mintPass: suspend () -> ProxyPass,
    ) {
        tokenRenewalJob?.cancel()
        tokenRenewalJob = scope.launch {
            var expiry = initialExpiry
            AppLogger.i(
                TAG,
                "proxy pass renewal scheduled in ${proxyPassRenewalDelayMs(expiry) / 1_000}s " +
                    (if (expiry == null) "(pass lifetime unknown; using a fixed interval)" else "(at half of its remaining life)"),
            )
            while (isActive) {
                delay(proxyPassRenewalDelayMs(expiry))
                if (myGeneration != connectionGeneration) return@launch

                val session = upstreamSession
                if (session == null || !session.isConnected) {
                    AppLogger.d(TAG, "skipping proxy pass renewal: no live session (the watchdog's redial mints its own)")
                    continue
                }

                val attempt = runCatching { mintPass() }
                val renewed = attempt.getOrNull()
                if (renewed == null) {
                    val error = attempt.exceptionOrNull()
                    if (error is CancellationException) throw error
                    if (error != null && isFatalUpstreamError(error)) {
                        AppLogger.e(
                            TAG,
                            "proxy pass renewal failed for a reason retrying cannot fix; leaving the session to the watchdog",
                            error,
                        )
                        return@launch
                    }
                    AppLogger.w(TAG, "proxy pass renewal failed; retrying shortly: ${error?.message}")

                    expiry = null
                    delay(PROXY_PASS_RENEWAL_RETRY_MS)
                    continue
                }

                if (myGeneration != connectionGeneration) return@launch

                upstreamSession?.updateBearerToken(renewed.token)
                AppLogger.i(TAG, "proxy pass renewed and swapped into the live session")
                expiry = renewed.expiresAtEpochSeconds
            }
        }
    }

    private suspend fun connect() {

        val myGeneration = ++connectionGeneration

        lastUnhealthyRedialAt = 0L

        boundNetwork = null

        EdgeAddressResolver.invalidate()
        runCatching {
            AppLogger.i(TAG, "connect: starting")
            val tokenStore = TokenStore(applicationContext)
            if (tokenStore.loadAuth() == null) error("Not signed in")
            val proxyStateStore = ProxyStateStore(applicationContext)
            val settingsStore = SettingsStore(applicationContext)

            val primaryCandidate = resolveConnectCandidate(proxyStateStore)
            ensureGenerationCurrent(myGeneration)

            var candidates = listOf(primaryCandidate)
            var candidateIndex = 0
            var alternatesDiscovered = false

            fun activeCandidate(): ProxyCandidate = candidates[candidateIndex.coerceIn(0, candidates.lastIndex)]

            suspend fun rotateCandidate(): Boolean {
                if (candidateIndex + 1 >= candidates.size && !alternatesDiscovered) {
                    alternatesDiscovered = true
                    val fresh = discoverAlternateCandidates(primaryCandidate)
                        .filter { discovered -> candidates.none { known -> known.authority == discovered.authority } }
                        .take(MAX_ALTERNATE_EDGES)
                    if (fresh.isNotEmpty()) {
                        candidates = candidates + fresh
                        AppLogger.i(
                            TAG,
                            "found ${fresh.size} alternate edge(s) in ${primaryCandidate.countryCode} to fail over to",
                        )
                    }
                }
                if (candidateIndex + 1 >= candidates.size) return false
                candidateIndex++
                AppLogger.i(
                    TAG,
                    "failing over to edge ${activeCandidate().authority} " +
                        "(${candidateIndex + 1} of ${candidates.size})",
                )
                return true
            }

            val upstreamProxyConfig = if (settingsStore.upstreamProxyEnabled && settingsStore.upstreamProxyHost.isNotBlank()) {
                UpstreamProxyConfig(
                    type = when (settingsStore.upstreamProxyType) {
                        SettingsStore.UpstreamProxyType.SOCKS5 -> UpstreamProxyConfig.Type.SOCKS5
                        SettingsStore.UpstreamProxyType.HTTP -> UpstreamProxyConfig.Type.HTTP
                    },
                    host = settingsStore.upstreamProxyHost,
                    port = settingsStore.upstreamProxyPort,
                    username = settingsStore.upstreamProxyUsername.ifBlank { null },
                    password = settingsStore.upstreamProxyPassword.ifBlank { null },
                )
            } else {
                null
            }
            val chainSuffix = if (upstreamProxyConfig != null) {
                " via ${upstreamProxyConfig.type} proxy ${upstreamProxyConfig.host}:${upstreamProxyConfig.port}"
            } else {
                ""
            }

            val customEdgeAddress = settingsStore.effectiveCustomEdgeAddress
            val edgeSuffix = if (customEdgeAddress != null) {
                " via pinned edge $customEdgeAddress:${primaryCandidate.port}"
            } else {
                ""
            }

            val dohEndpointAddresses = settingsStore.dohProvider.addresses

            AppLogger.i(
                TAG,
                "connect: target=${primaryCandidate.authority} country=${primaryCandidate.countryCode}$chainSuffix$edgeSuffix",
            )

            val socksPort = settingsStore.socksPort
            val socksBindAddress = settingsStore.socksBindAddress
            val proxyOnlyMode = settingsStore.proxyOnlyMode
            val excludedApps = settingsStore.excludedApps

            val customDnsServer = settingsStore.effectiveCustomDnsServer

            fun connectedLabel(): String = if (proxyOnlyMode) {

                "Proxy active \u2022 $socksBindAddress:$socksPort"
            } else {
                val target = activeCandidate()
                "Connected \u2022 ${target.countryName.ifBlank { target.countryCode }}"
            }

            if (proxyOnlyMode) {
                ControlPlaneHttp.socketProtector = null
            } else {
                ControlPlaneHttp.socketProtector = { socket -> protect(socket) }
            }

            val mapdnsActive = !proxyOnlyMode && customDnsServer == null
            val socks = LocalSocks5Server(
                socksBindAddress,
                socksPort,
                mapdnsActive,
                { upstreamSession },
            ) {
                onUpstreamSessionUnhealthy()
            }
            socks.start()
            socksServer = socks
            ensureGenerationCurrent(myGeneration)

            if (proxyOnlyMode) {

                tunnelInterfaceActive = false
                AppLogger.i(
                    TAG,
                    "connect: proxy-only mode enabled, skipping TUN interface and tun2socks; " +
                        "local proxy at $socksBindAddress:$socksPort",
                )
            } else {
                val fd = establishTun(excludedApps, customDnsServer) ?: error("Failed to establish TUN interface")
                tunFd = fd
                tunnelInterfaceActive = true

                reportedUnderlying = null
                runCatching {
                    getSystemService(ConnectivityManager::class.java)?.activeNetwork?.let { active ->
                        reportUnderlyingNetwork(active)
                    }
                }

                val tunnelConfigPath = HevSocks5TunnelConfig.write(
                    applicationContext,
                    socksPort,
                    customDnsServer,
                )
                val tunnelStarted = HevSocks5Tunnel.start(tunnelConfigPath, fd.fd)
                if (!tunnelStarted) error("Failed to start the tun2socks tunnel (hev-socks5-tunnel)")
                AppLogger.i(TAG, "connect: TUN interface up, hev-socks5-tunnel started")
                delay(POST_TUN_SETTLE_MS)
            }
            ensureGenerationCurrent(myGeneration)

            suspend fun mintProxyPass(): ProxyPass {
                var auth = tokenStore.loadAuth() ?: throw TokenInvalidError("Not signed in")

                if (!tokenStore.hasValidSession()) {
                    val renewed = renewAccessToken(tokenStore)
                    if (renewed != null) {
                        auth = renewed
                        AppLogger.i(TAG, "the account's access token had expired and was renewed")
                    } else {

                        AppLogger.w(
                            TAG,
                            "the account's access token is past its expiry and could not be renewed automatically; " +
                                "you may need to sign in again",
                        )
                    }
                }
                val guardian = GuardianClient()

                return try {
                    guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
                } catch (invalid: TokenInvalidError) {
                    AppLogger.w(TAG, "proxy pass rejected, activating Guardian entitlement and retrying", invalid)
                    guardian.activateGuardian(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
                    guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
                }
            }

            var currentPassExpiry: Long? = null

            suspend fun dialUpstream(): H2UpstreamSession {
                val target = activeCandidate()
                val pass = mintProxyPass()
                currentPassExpiry = pass.expiresAtEpochSeconds
                val lifetimeNote = pass.expiresAtEpochSeconds?.let { expiresAt ->
                    val seconds = (expiresAt * 1_000L - System.currentTimeMillis()) / 1_000L
                    " (valid for ${seconds}s)"
                } ?: " (lifetime not stated)"
                AppLogger.i(TAG, "connect: acquired Guardian proxy pass$lifetimeNote")

                val edgeAddress = customEdgeAddress
                    ?: resolveEdgeAddress(target.host, upstreamProxyConfig, dohEndpointAddresses)

                val session = H2UpstreamSession(
                    target.host,
                    target.port,
                    pass.token,
                    upstreamProxyConfig,
                    edgeAddress,
                )
                try {
                    session.connect()
                } catch (failure: Throwable) {

                    runCatching { session.close() }
                    throw failure
                }
                AppLogger.i(TAG, "connect: upstream HTTP/2 tunnel established to ${target.authority}")
                return session
            }

            var dialAttempt = 0
            var lastDialFailure: Throwable? = null
            while (true) {
                dialAttempt++
                ensureGenerationCurrent(myGeneration)
                val target = activeCandidate()

                val session = try {
                    withTimeout(CONNECT_TIMEOUT_MS) { dialUpstream() }
                } catch (cancellation: CancellationException) {

                    if (cancellation !is TimeoutCancellationException) throw cancellation
                    lastDialFailure = cancellation
                    AppLogger.w(TAG, "dial to ${target.authority} timed out (attempt $dialAttempt)")
                    null
                } catch (error: Throwable) {

                    if (isFatalUpstreamError(error)) throw error
                    lastDialFailure = error
                    AppLogger.w(TAG, "dial to ${target.authority} failed (attempt $dialAttempt): ${error.message}")
                    null
                }

                if (session != null) {
                    if (myGeneration != connectionGeneration) {

                        runCatching { session.close() }
                        ensureGenerationCurrent(myGeneration)
                    }
                    upstreamSession = session
                    delay(INITIAL_DIAL_SETTLE_MS)
                    if (upstreamSession?.isConnected == true) break
                    AppLogger.w(
                        TAG,
                        "upstream tunnel to ${target.authority} died immediately after connecting " +
                            "(attempt $dialAttempt/$INITIAL_DIAL_MAX_ATTEMPTS)",
                    )
                    runCatching { upstreamSession?.close() }
                    upstreamSession = null

                    lastDialFailure = null
                }

                if (dialAttempt >= INITIAL_DIAL_MAX_ATTEMPTS) {

                    val (failures, cleared) = proxyStateStore.recordFailure()
                    if (cleared) {
                        AppLogger.w(
                            TAG,
                            "the saved location failed $failures connects in a row; clearing it so the next " +
                                "connect auto-selects a location",
                        )
                    }
                    val failure = lastDialFailure
                    if (failure != null) {
                        error(
                            "Could not reach a VPN server after $dialAttempt attempts across " +
                                "${candidates.size} server(s). Last error: ${friendlyErrorMessage(failure)}",
                        )
                    }
                    error(
                        "The VPN server closed the connection immediately, $dialAttempt times in a row. " +
                            "Try again shortly or pick a different location.",
                    )
                }

                rotateCandidate()
                delay(fullJitterBackoffMs(dialAttempt - 1, INITIAL_DIAL_BACKOFF_BASE_MS, INITIAL_DIAL_BACKOFF_CAP_MS))
            }

            ensureGenerationCurrent(myGeneration)

            val establishedCandidate = activeCandidate()

            if (establishedCandidate.authority == primaryCandidate.authority) {
                proxyStateStore.save(primaryCandidate)
            }

            val connectedText = connectedLabel()
            _state.value = ConnectionState.CONNECTED
            statusLabel = connectedText
            updateNotification(connectedText)
            AppLogger.i(TAG, "connect: CONNECTED via ${establishedCandidate.authority}")

            startProxyPassRenewal(myGeneration, currentPassExpiry) { mintProxyPass() }

            if (settingsStore.exitCheckEnabled) {
                scope.launch {
                    ExitCheck().verifyExitCountry(socksPort, establishedCandidate.countryCode)
                        .onSuccess { observed ->
                            if (myGeneration == connectionGeneration) {
                                AppLogger.i(TAG, "exit check: observed country=$observed")
                            }
                        }
                        .onFailure { AppLogger.w(TAG, "exit check failed (non-fatal)", it) }
                }
            }

            if (!proxyOnlyMode) {
                startSpeedUpdates()
            }

            watchdogJob = scope.launch {
                var consecutiveFailures = 0
                var waitingForNetwork = false

                suspend fun stopWithFatalError(message: String) {
                    AppLogger.e(TAG, "unrecoverable upstream failure; disconnecting: $message")
                    _lastError.value = message
                    connectionGeneration++
                    watchdogJob = null
                    _state.value = ConnectionState.DISCONNECTED
                    exitForeground()
                    releaseWakeLocks()
                    val doomed = detachResources()
                    opMutex.withLock { releaseResources(doomed, stopNativeTunnel = true) }
                    stopSelf()
                }

                while (isActive) {
                    delay(UPSTREAM_WATCHDOG_INTERVAL_MS)
                    val current = upstreamSession
                    if (current != null && current.isConnected) {
                        if (consecutiveFailures > 0 || waitingForNetwork) {
                            AppLogger.i(TAG, "upstream tunnel is healthy again")
                            _lastError.value = null
                            statusLabel = connectedLabel()
                            updateNotification(statusLabel)
                        }
                        consecutiveFailures = 0
                        waitingForNetwork = false
                        continue
                    }

                    if (!hasUsableNetwork()) {
                        if (!waitingForNetwork) {
                            AppLogger.i(TAG, "no usable network; holding the session and waiting for connectivity")
                            waitingForNetwork = true
                            statusLabel = "Waiting for network\u2026"
                            updateNotification(statusLabel)
                        }
                        consecutiveFailures = 0
                        continue
                    }
                    if (waitingForNetwork) {
                        AppLogger.i(TAG, "network is back; redialing the upstream tunnel")
                        waitingForNetwork = false
                    }

                    AppLogger.w(TAG, "upstream tunnel is down; attempting to reconnect")
                    runCatching { current?.close() }
                    statusLabel = "Reconnecting\u2026"
                    updateNotification(statusLabel)

                    val fresh = try {
                        withTimeout(CONNECT_TIMEOUT_MS) { dialUpstream() }
                    } catch (cancellation: CancellationException) {
                        if (cancellation !is TimeoutCancellationException) throw cancellation
                        consecutiveFailures++
                        AppLogger.w(TAG, "upstream reconnect attempt $consecutiveFailures timed out")
                        null
                    } catch (error: Throwable) {
                        if (isFatalUpstreamError(error)) {
                            stopWithFatalError(friendlyErrorMessage(error))
                            return@launch
                        }
                        consecutiveFailures++
                        AppLogger.w(TAG, "upstream reconnect attempt $consecutiveFailures failed: ${error.message}")
                        null
                    }

                    if (fresh == null) {

                        if (consecutiveFailures % RECONNECT_FAILURES_BEFORE_EDGE_ROTATION == 0) {
                            rotateCandidate()
                        }

                        if (consecutiveFailures == RECONNECT_FAILURES_BEFORE_WARNING) {
                            _lastError.value =
                                "Still trying to reconnect to the VPN server. Tap Disconnect to stop."
                            AppLogger.e(
                                TAG,
                                "$consecutiveFailures consecutive reconnect failures; continuing to retry with backoff " +
                                    "(capped at ${RECONNECT_BACKOFF_CAP_MS / 1_000}s between attempts)",
                            )
                        }
                        delay(fullJitterBackoffMs(consecutiveFailures - 1, RECONNECT_BACKOFF_BASE_MS, RECONNECT_BACKOFF_CAP_MS))
                        continue
                    }

                    if (myGeneration != connectionGeneration) {

                        AppLogger.i(TAG, "discarding stale reconnect from a torn-down session generation")
                        runCatching { fresh.close() }
                        return@launch
                    }
                    upstreamSession = fresh
                    consecutiveFailures = 0
                    _lastError.value = null

                    statusLabel = connectedLabel()
                    updateNotification(statusLabel)
                    AppLogger.i(TAG, "upstream tunnel reconnected via ${activeCandidate().authority}")

                    startProxyPassRenewal(myGeneration, currentPassExpiry) { mintProxyPass() }
                }
            }
        }.onFailure { failure ->

            if (failure is CancellationException) {
                AppLogger.i(TAG, "connect: aborted because the connection was cancelled")
                releaseResources(detachResources(), stopNativeTunnel = true)
                return@onFailure
            }
            val message = if (failure is TimeoutCancellationException) {
                "Connection timed out. Check your network and try again."
            } else {
                friendlyErrorMessage(failure)
            }
            AppLogger.e(TAG, "connect failed", failure)
            _lastError.value = message
            teardown()
            exitForeground()
            releaseWakeLocks()
            stopSelf()
        }
        connectJob = null
    }

    private suspend fun resolveConnectCandidate(proxyStateStore: ProxyStateStore): ProxyCandidate {
        proxyStateStore.load()?.let { return it }
        AppLogger.i(TAG, "connect: no server previously selected; auto-selecting recommended location")
        val countries = withTimeout(SERVER_LIST_FETCH_TIMEOUT_MS) { ServerListClient().fetchCountries() }
        val recommended = ServerListClient.candidatesForCountry(countries, RECOMMENDED_COUNTRY_CODE).randomOrNull()
        val chosen = recommended ?: countries.firstOrNull { it.code.isNotBlank() }?.let { country ->
            ServerListClient.candidatesForCountry(countries, country.code).randomOrNull()
        } ?: error("No server selected and no servers are available. Choose a location first.")
        proxyStateStore.save(chosen)
        AppLogger.i(TAG, "connect: auto-selected ${chosen.authority} country=${chosen.countryCode}")
        return chosen
    }

    private suspend fun discoverAlternateCandidates(primary: ProxyCandidate): List<ProxyCandidate> {
        val attempt = runCatching {
            val countries = withTimeout(SERVER_LIST_FETCH_TIMEOUT_MS) { ServerListClient().fetchCountries() }
            val sameCity = ServerListClient.candidatesForCity(countries, primary.countryCode, primary.cityCode)
            val sameCountry = ServerListClient.candidatesForCountry(countries, primary.countryCode)
            (sameCity + sameCountry)
                .distinctBy { it.authority }
                .filter { it.authority != primary.authority }
        }
        val failure = attempt.exceptionOrNull()
        if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
        if (failure != null) {
            AppLogger.w(TAG, "could not fetch alternate edges; staying with ${primary.authority}", failure)
            return emptyList()
        }
        return attempt.getOrDefault(emptyList())
    }

    private suspend fun resolveEdgeAddress(
        host: String,
        upstreamProxy: UpstreamProxyConfig?,
        dohEndpointAddresses: List<String>,
    ): String? {
        if (upstreamProxy != null) return null
        if (dohEndpointAddresses.isEmpty()) return null
        return EdgeAddressResolver.resolve(host, dohEndpointAddresses)
    }

    private suspend fun renewAccessToken(tokenStore: TokenStore): RuntimeAuth? {
        val attempt = runCatching { FxaAuthRepository(tokenStore).refreshAccessToken() }
        val failure = attempt.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (failure != null) {
            AppLogger.w(TAG, "renewing the account's access token failed", failure)
            return null
        }
        return attempt.getOrNull()
    }

    private fun friendlyErrorMessage(error: Throwable): String = when (error) {
        is QuotaExceededError -> "Your VPN quota is exhausted. Try again later."
        is TokenInvalidError -> "Your session was rejected. Please sign in again."
        is TimeoutCancellationException -> "the connection timed out"
        else -> error.message ?: (error::class.simpleName ?: "Connection failed")
    }

    private fun establishTun(excludedApps: Set<String>, customDnsServer: String?): ParcelFileDescriptor? {
        val dnsServer = customDnsServer ?: HevSocks5TunnelConfig.MAPDNS_ADDRESS
        if (customDnsServer != null) {
            AppLogger.i(TAG, "establishTun: dns=$customDnsServer (user-selected, resolved over the tunnel)")
        } else {
            AppLogger.i(TAG, "establishTun: dns=mapdns (${HevSocks5TunnelConfig.MAPDNS_ADDRESS}), resolved on-device")
        }
        return Builder()
            .setSession("FoxyVPN")
            .addAddress(HevSocks5TunnelConfig.TUN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .apply {

                val ipv6Address = runCatching {
                    addAddress(TUN_ADDRESS_IPV6, TUN_PREFIX_LENGTH_IPV6)
                }.onFailure {
                    AppLogger.w(
                        TAG,
                        "could not add an IPv6 address to the TUN interface; continuing IPv4-only " +
                            "(IPv6 traffic will not be routed into the tunnel)",
                        it,
                    )
                }
                if (ipv6Address.isSuccess) {
                    runCatching { addRoute("::", 0) }.onFailure {
                        AppLogger.w(
                            TAG,
                            "added an IPv6 address but could not route ::/0; IPv6 traffic will bypass the tunnel",
                            it,
                        )
                    }
                }
            }
            .addDnsServer(dnsServer)

            .setMtu(HevSocks5TunnelConfig.TUN_MTU)
            .setBlocking(true)
            .apply {
                runCatching { addDisallowedApplication(packageName) }
                    .onFailure { AppLogger.w(TAG, "failed to exclude own app from the VPN tunnel", it) }
                for (excludedPackage in excludedApps) {
                    runCatching { addDisallowedApplication(excludedPackage) }
                        .onFailure { AppLogger.w(TAG, "failed to exclude $excludedPackage from the VPN tunnel (app may be uninstalled)", it) }
                }
            }
            .establish()
    }

    private fun teardown() {
        connectionGeneration++
        watchdogJob?.cancel()
        watchdogJob = null
        releaseResources(detachResources(), stopNativeTunnel = true)
        _state.value = ConnectionState.DISCONNECTED
    }

    private fun detachResources(): SessionResources {
        speedJob?.cancel()
        speedJob = null

        tokenRenewalJob?.cancel()
        tokenRenewalJob = null
        val detached = SessionResources(tunFd, socksServer, upstreamSession)
        tunFd = null
        tunnelInterfaceActive = false
        socksServer = null
        upstreamSession = null
        return detached
    }

    private fun releaseResources(resources: SessionResources, stopNativeTunnel: Boolean) {
        if (resources.isEmpty && !stopNativeTunnel) return
        if (stopNativeTunnel) {
            runCatching { HevSocks5Tunnel.stop() }.onFailure { AppLogger.w(TAG, "error stopping tun2socks tunnel", it) }
        }
        runCatching { resources.socksServer?.stop() }.onFailure { AppLogger.w(TAG, "error stopping local SOCKS5 server", it) }
        runCatching { resources.upstreamSession?.close() }.onFailure { AppLogger.w(TAG, "error closing upstream session", it) }
        runCatching { resources.tunFd?.close() }.onFailure { AppLogger.w(TAG, "error closing TUN interface", it) }
    }

    override fun onRevoke() {
        requestDisconnect("VPN permission was revoked by the system")
        super.onRevoke()
    }

    private fun startSpeedUpdates() {
        speedJob?.cancel()
        speedJob = scope.launch {
            var lastStats = HevSocks5Tunnel.stats()
            var lastSampleAt = System.currentTimeMillis()
            while (isActive) {
                delay(SPEED_UPDATE_INTERVAL_MS)
                val stats = HevSocks5Tunnel.stats()
                val now = System.currentTimeMillis()
                val elapsedSeconds = ((now - lastSampleAt).coerceAtLeast(1)).toDouble() / 1_000.0

                val txRate = ((stats[1] - lastStats[1]).coerceAtLeast(0) / elapsedSeconds).toLong()
                val rxRate = ((stats[3] - lastStats[3]).coerceAtLeast(0) / elapsedSeconds).toLong()
                lastStats = stats
                lastSampleAt = now
                if (_state.value != ConnectionState.CONNECTED) continue
                updateNotification(
                    statusLabel,
                    "\u2193 ${formatBytesPerSecond(rxRate)}  \u2191 ${formatBytesPerSecond(txRate)}",
                )
            }
        }
    }

    private fun buildNotification(text: String, subText: String? = null): Notification {
        val openIntent = PendingIntent.getActivity(
            this, REQUEST_CODE_OPEN, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val disconnectIntent = PendingIntent.getService(
            this, REQUEST_CODE_DISCONNECT, Intent(this, FoxyVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, FoxyVpnApp.VPN_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("FoxyVPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .setOngoing(true)

            .setOnlyAlertOnce(true)

            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

            .setLocalOnly(true)

            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectIntent)
        if (subText != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$subText"))
        }
        return builder.build()
    }

    private fun enterForeground(text: String) {
        foregroundActive = true
        lastNotificationText = text
        startForeground(NOTIFICATION_ID, buildNotification(text))
    }

    private fun exitForeground() {
        foregroundActive = false
        lastNotificationText = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun updateNotification(text: String, subText: String? = null) {
        if (!foregroundActive) return
        val key = if (subText == null) text else "$text\n$subText"
        if (key == lastNotificationText) return
        lastNotificationText = key
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text, subText)) }
    }

    companion object {
        const val ACTION_CONNECT = "com.vauth.foxyvpn.action.CONNECT"
        const val ACTION_DISCONNECT = "com.vauth.foxyvpn.action.DISCONNECT"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_CODE_OPEN = 0
        private const val REQUEST_CODE_DISCONNECT = 1

        private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
        val state: StateFlow<ConnectionState> = _state

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FoxyVpnService::class.java).setAction(ACTION_CONNECT))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FoxyVpnService::class.java).setAction(ACTION_DISCONNECT))
        }
    }
}
