package com.vauth.foxyvpn.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.vauth.foxyvpn.BuildConfig
import com.vauth.foxyvpn.FoxyVpnApp
import com.vauth.foxyvpn.MainActivity
import com.vauth.foxyvpn.data.AppLogger
import com.vauth.foxyvpn.data.ControlPlaneHttp
import com.vauth.foxyvpn.data.FxaRefreshFailure
import com.vauth.foxyvpn.data.GUARDIAN_ENDPOINT_DEFAULT
import com.vauth.foxyvpn.data.GuardianClient
import com.vauth.foxyvpn.data.GuardianHttpError
import com.vauth.foxyvpn.data.ProxyPass
import com.vauth.foxyvpn.data.ProxyStateStore
import com.vauth.foxyvpn.data.QuotaExceededError
import com.vauth.foxyvpn.data.RECOMMENDED_COUNTRY_CODE
import com.vauth.foxyvpn.data.ServerListClient
import com.vauth.foxyvpn.data.SettingsStore
import com.vauth.foxyvpn.data.TokenInvalidError
import com.vauth.foxyvpn.data.TunnelSettings
import com.vauth.foxyvpn.data.awaitResponse
import com.vauth.foxyvpn.data.formatBytesPerSecond
import com.vauth.foxyvpn.data.model.ConnectionState
import com.vauth.foxyvpn.data.model.ProxyCandidate
import com.vauth.foxyvpn.data.retryDeadlineMillis
import com.vauth.foxyvpn.data.retryDelayMillis
import com.vauth.foxyvpn.data.tunnelSnapshot
import com.vauth.foxyvpn.widget.QuickConnectWidgetProvider
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class VpnLiveStats(
    val txRateBytesPerSec: Long = 0L,
    val rxRateBytesPerSec: Long = 0L,
    val sessionBytes: Long = 0L,
)

private const val TAG = "FoxyVpnService"
private const val SERVER_LIST_FETCH_TIMEOUT_MS = 15_000L
private const val PROXY_PASS_FETCH_BUDGET_MS = 60_000L
private const val PROXY_PASS_RETRY_MS = 5_000L
private const val WAKE_LOCK_TAG = "com.vauth.foxyvpn:vpn-cpu"
private const val WIFI_LOCK_TAG = "com.vauth.foxyvpn:vpn-wifi"

private fun proxyPassDeadline(expiresAtEpochSeconds: Long?): Long =
    credentialDeadlineMillis(
        expiresAtEpochSeconds,
        System.currentTimeMillis(),
        SystemClock.elapsedRealtime(),
    )

class FoxyVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionInvalidationListener: () -> Unit = {
        requestDisconnect("account session was cleared")
    }
    private val opMutex = nativeMutex

    @Volatile private var commandServer: CommandServer? = null
    private var connectJob: Job? = null
    private var retryJob: Job? = null
    private var trafficMonitor: NativeTrafficMonitor? = null
    private var pingJob: Job? = null
    private var exitCheckJob: Job? = null
    private var healthJob: Job? = null
    private var connectionSettings: TunnelSettings? = null
    private var connectionCandidate: ProxyCandidate? = null
    private var directoryRefreshAfterElapsedMs = 0L
    private val pingMutex = Mutex()
    @Volatile private var activeSocksPort: Int? = null
    @Volatile private var activeProxyCredentials: Pair<String, String> = "" to ""
    private var tokenRenewalJob: Job? = null
    private var credentialExpiryJob: Job? = null
    private val installedCredentialDeadline = AtomicLong(0L)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewalJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    internal var nativeProtectionRequired = true
        private set

    @Volatile private var tunnelInterfaceActive = false
    @Volatile private var boundNetwork: Network? = null
    @Volatile private var reportedUnderlying: Network? = null
    @Volatile private var statusLabel: String = "Connecting\u2026"
    @Volatile private var foregroundActive = false
    @Volatile private var lastNotificationText: String? = null
    @Volatile private var lastNotificationSubText: String? = null
    private val connectionGeneration = AtomicInteger()
    @Volatile private var stopping = false
    private var libboxReady = false
    private var healthRecoveryInProgress = false
    private var reconnectAttempt = 0
    private val retryNotBeforeElapsedMs = AtomicLong(0L)
    private var controllerSecret = UUID.randomUUID().toString()
    @Volatile private var activePlatformInterface: FoxyPlatformInterface? = null

    private class SessionResources(
        val commandServer: CommandServer?,
        val platformInterface: FoxyPlatformInterface?,
    ) {
        val isEmpty: Boolean
            get() = commandServer == null && platformInterface == null
    }

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                reportUnderlyingNetwork(network)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                reportUnderlyingNetwork(network, capabilities)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                reportUnderlyingNetwork(network)
                if (boundNetwork == network) activePlatformInterface?.notifyDefaultInterface()
            }

            override fun onLost(network: Network) {
                if (boundNetwork != network) return
                boundNetwork = null
                reportedUnderlying = null

                if (tunnelInterfaceActive) runCatching { setUnderlyingNetworks(null) }
                firstUsableNetwork()?.let { reportUnderlyingNetwork(it) }
                activePlatformInterface?.notifyDefaultInterface()
            }
        }

    private fun reportUnderlyingNetwork(
        network: Network,
        capabilities: NetworkCapabilities? = null,
    ) {
        val caps =
            capabilities
                ?: getSystemService(ConnectivityManager::class.java)
                    ?.getNetworkCapabilities(network)
        if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return

        if (firstUsableNetwork() != network) return

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

        boundNetwork = network
        runCatching { ControlPlaneHttp.client.connectionPool.evictAll() }
        activePlatformInterface?.notifyDefaultInterface()

        if (!stopping && _state.value == ConnectionState.CONNECTING && connectJob == null) {
            connectJob = scope.launch { opMutex.withLock { connect() } }
        }
    }

    private fun ensureLibboxSetup() {
        if (libboxReady) return
        val baseDir = filesDir
        val workingDir = filesDir
        val tempDir = cacheDir
        val options =
            SetupOptions().apply {
                basePath = baseDir.absolutePath
                workingPath = workingDir.absolutePath
                tempPath = tempDir.absolutePath
                logMaxLines = 5000
                debug = BuildConfig.DEBUG
            }
        Libbox.setup(options)
        libboxReady = true
    }

    override fun onCreate() {
        super.onCreate()
        _state.value = ConnectionState.DISCONNECTED
        (applicationContext as FoxyVpnApp).tokenStore.onSessionInvalidated =
            sessionInvalidationListener
        runCatching {
                val underlyingNetworks =
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build()
                getSystemService(ConnectivityManager::class.java)
                    ?.registerNetworkCallback(underlyingNetworks, networkCallback)
            }
            .onFailure { AppLogger.w(TAG, "could not register network callback", it) }

        scope.launch {
            _state.collect { state ->
                QuickConnectWidgetProvider.updateAll(this@FoxyVpnService, state)
            }
        }
    }

    override fun onDestroy() {
        stopping = true
        synchronized(credentialCommitLock) {
            connectionGeneration.incrementAndGet()
            if (coreOwner === this) Libbox.setBearerToken("")
        }
        connectJob?.cancel()
        retryJob?.cancel()
        tokenRenewalJob?.cancel()
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(networkCallback)
        }

        stopMonitoring()
        releaseWakeLocks()
        val server = commandServer
        CoroutineScope(Dispatchers.IO).launch {
            try {
                interruptNativeStart(server)
                opMutex.withLock { teardown() }
            } finally {
                scope.cancel()
            }
        }
        (applicationContext as FoxyVpnApp)
            .tokenStore
            .clearSessionInvalidationListener(sessionInvalidationListener)
        super.onDestroy()
    }

    @Synchronized
    private fun acquireWakeLocks() {
        if (stopping) return
        if (wakeLock == null) {
            runCatching {
                    val power =
                        applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                            ?: error("PowerManager unavailable")
                    wakeLock =
                        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                            setReferenceCounted(false)
                            acquire(300_000L)
                        }
                }
                .onFailure { AppLogger.d(TAG, "CPU wake lock unavailable: ${it.message}") }
        }
        if (wakeLock != null && wakeLockRenewalJob?.isActive != true) {
            wakeLockRenewalJob =
                scope.launch {
                    while (isActive) {
                        delay(180_000L)
                        synchronized(this@FoxyVpnService) {
                            if (!stopping)
                                runCatching { wakeLock?.acquire(300_000L) }
                                    .onFailure { AppLogger.w(TAG, "CPU wake lock renewal failed") }
                        }
                    }
                }
        }
        if (wifiLock == null) {
            runCatching {
                    val wifi =
                        applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                            ?: error("WifiManager unavailable")
                    @Suppress("DEPRECATION")
                    wifiLock =
                        wifi
                            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)
                            .apply {
                                setReferenceCounted(false)
                                acquire()
                            }
                }
                .onFailure { AppLogger.d(TAG, "Wi-Fi lock unavailable: ${it.message}") }
        }
    }

    @Synchronized
    private fun releaseWakeLocks() {
        wakeLockRenewalJob?.cancel()
        wakeLockRenewalJob = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { AppLogger.d(TAG, "error releasing CPU wake lock: ${it.message}") }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            .onFailure { AppLogger.d(TAG, "error releasing Wi-Fi lock: ${it.message}") }
        wifiLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_CONNECT
        if (action == ACTION_CONNECT || action == VpnService.SERVICE_INTERFACE) {
            if (stopping) return START_NOT_STICKY
            if (_state.value != ConnectionState.DISCONNECTED) {
                AppLogger.d(TAG, "ignoring duplicate connect request while ${_state.value}")
                enterForeground(statusLabel)
                return START_STICKY
            }
            _state.value = ConnectionState.CONNECTING
            _lastError.value = null
            statusLabel = "Connecting\u2026"
            enterForeground(statusLabel)

            acquireWakeLocks()
            connectJob = scope.launch { opMutex.withLock { connect() } }
            return START_STICKY
        } else if (action == ACTION_DISCONNECT) {
            requestDisconnect("requested by the user")
        }
        return START_NOT_STICKY
    }

    internal fun stopFromCore(platform: FoxyPlatformInterface) {
        synchronized(credentialCommitLock) {
            if (coreOwner === this && activePlatformInterface === platform && !stopping) {
                requestDisconnect("requested by sing-box")
            }
        }
    }

    private fun requestDisconnect(reason: String) {
        synchronized(credentialCommitLock) {
            if (stopping) return
            stopping = true
            connectionGeneration.incrementAndGet()
            if (coreOwner === this) Libbox.setBearerToken("")
        }
        connectJob?.cancel()
        retryJob?.cancel()
        tokenRenewalJob?.cancel()
        AppLogger.i(TAG, "disconnect: $reason")
        statusLabel = "Disconnecting…"
        stopMonitoring()
        releaseWakeLocks()
        val server = commandServer
        scope.launch {
            try {
                interruptNativeStart(server)
                opMutex.withLock { teardown() }
            } finally {
                exitForeground()
                releaseWakeLocks()
                stopSelf()
            }
        }
    }

    private fun interruptNativeStart(server: CommandServer?) {
        runCatching { server?.closeService() }
            .onFailure { AppLogger.w(TAG, "Could not interrupt the native session", it) }
    }

    private fun stopMonitoring() {
        trafficMonitor?.stop()
        trafficMonitor = null
        pingJob?.cancel()
        exitCheckJob?.cancel()
        healthJob?.cancel()
        credentialExpiryJob?.cancel()
    }

    private fun ensureGenerationCurrent(myGeneration: Int) {
        if (myGeneration != connectionGeneration.get()) {
            throw CancellationException("connect was superseded by a newer request")
        }
    }

    internal fun firstUsableNetwork(): Network? {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val networks = runCatching { manager.allNetworks }.getOrDefault(emptyArray())
        val ordered = (listOfNotNull(manager.activeNetwork, boundNetwork) + networks).distinct()
        var unvalidated: Network? = null
        for (network in ordered) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                return network
            if (unvalidated == null) unvalidated = network
        }
        return unvalidated
    }

    private fun isFatalUpstreamError(error: Throwable): Boolean =
        error is TokenInvalidError ||
            error is QuotaExceededError ||
            error is IllegalArgumentException ||
            error is com.vauth.foxyvpn.data.SecureStorageException ||
            (error is FxaRefreshFailure && error.permanent) ||
            (error is GuardianHttpError && error.statusCode in listOf(401, 403))

    private fun startProxyPassRenewal(
        generation: Int,
        initialDeadline: Long,
        sessionEpoch: Long,
        mintProxyPass: suspend () -> ProxyPass,
    ) {
        tokenRenewalJob?.cancel()
        tokenRenewalJob =
            scope.launch {
                var deadline = initialDeadline
                var nextDelay =
                    (deadline - SystemClock.elapsedRealtime() - PROXY_PASS_FETCH_BUDGET_MS)
                        .coerceAtLeast(0L)
                while (isActive) {
                    delay(nextDelay)
                    if (generation != connectionGeneration.get() || stopping) return@launch
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0L) {
                        _lastError.value =
                            "Proxy credentials could not be renewed in time. Reconnect to retry."
                        requestDisconnect("proxy pass renewal deadline reached")
                        return@launch
                    }
                    try {
                        val pass =
                            withTimeout(minOf(PROXY_PASS_FETCH_BUDGET_MS, remaining)) {
                                mintProxyPass()
                            }
                        opMutex.withLock {
                            val store = (applicationContext as FoxyVpnApp).tokenStore
                            store.withCurrentSession(sessionEpoch) {
                                synchronized(credentialCommitLock) {
                                    ensureGenerationCurrent(generation)
                                    validateProxyPass(pass)
                                    Libbox.setBearerToken(pass.token)
                                    installedCredentialDeadline.set(
                                        proxyPassDeadline(pass.expiresAtEpochSeconds)
                                    )
                                }
                            }
                        }
                        deadline = installedCredentialDeadline.get()
                        nextDelay =
                            (deadline - SystemClock.elapsedRealtime() - PROXY_PASS_FETCH_BUDGET_MS)
                                .coerceAtLeast(0L)
                        AppLogger.i(TAG, "proxy pass replaced through the scoped core API")
                    } catch (error: Exception) {
                        if (
                            error is CancellationException && error !is TimeoutCancellationException
                        )
                            throw error
                        if (isFatalUpstreamError(error)) {
                            _lastError.value = friendlyErrorMessage(error)
                            requestDisconnect("credential renewal was rejected")
                            return@launch
                        }
                        AppLogger.w(
                            TAG,
                            "proxy pass renewal failed; retrying within its validity deadline",
                            error,
                        )
                        nextDelay =
                            minOf(
                                maxOf(
                                    PROXY_PASS_RETRY_MS,
                                    (error as? GuardianHttpError)?.retryAfterMs ?: 0L,
                                ),
                                (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
                            )
                    }
                }
            }
    }

    private fun startCredentialExpiryWatchdog(generation: Int) {
        credentialExpiryJob?.cancel()
        credentialExpiryJob =
            scope.launch {
                while (isActive) {
                    val remaining =
                        installedCredentialDeadline.get() - SystemClock.elapsedRealtime()
                    if (remaining > 0L) {
                        delay(remaining)
                        continue
                    }
                    synchronized(credentialCommitLock) {
                        if (
                            stopping ||
                                coreOwner !== this@FoxyVpnService ||
                                generation != connectionGeneration.get()
                        )
                            return@launch

                        if (SystemClock.elapsedRealtime() >= installedCredentialDeadline.get()) {
                            _lastError.value = "Proxy credentials expired. Reconnect to retry."
                            requestDisconnect("installed credential deadline reached")
                            return@launch
                        }
                    }
                }
            }
    }

    private fun validateProxyPass(pass: ProxyPass) {
        require(
            pass.token.isNotBlank() && !pass.token.contains('\r') && !pass.token.contains('\n')
        ) {
            "Guardian returned an invalid proxy credential"
        }
        val expiry = pass.expiresAtEpochSeconds
        require(expiry == null || expiry - System.currentTimeMillis() / 1_000L > 60L) {
            "Guardian returned a proxy credential with insufficient lifetime"
        }
    }

    private suspend fun connect() {
        if (_state.value == ConnectionState.CONNECTED) {
            AppLogger.d(TAG, "connect: already connected, skipping duplicate connect request")
            return
        }
        if (stopping) throw CancellationException("Service is stopping")

        val retryWait =
            (retryNotBeforeElapsedMs.get() - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (retryWait > 0) {
            connectJob = null
            scheduleReconnect(retryWait)
            return
        }
        if (stopping) throw CancellationException("Service is stopping")
        val previousOwner = coreOwner
        if (previousOwner != null && previousOwner !== this) previousOwner.teardown()
        synchronized(credentialCommitLock) { coreOwner = this }
        val myGeneration = connectionGeneration.incrementAndGet()
        boundNetwork = null

        runCatching {
                AppLogger.i(TAG, "connect: starting sing-box engine")
                val app = applicationContext as FoxyVpnApp
                val tokenStore = app.tokenStore
                val sessionEpoch = tokenStore.sessionEpoch
                if (tokenStore.loadAuth() == null) {
                    tokenStore.storageError.value?.let {
                        throw com.vauth.foxyvpn.data.SecureStorageException(
                            IllegalStateException(it)
                        )
                    }
                    throw TokenInvalidError("Not signed in")
                }
                val proxyStateStore = app.proxyStateStore
                val settingsStore = app.settingsStore
                val tunnelSettings =
                    connectionSettings
                        ?: settingsStore.tunnelSnapshot().also { connectionSettings = it }
                controllerSecret = UUID.randomUUID().toString()

                val directoryClient = app.serverListClient
                val existingCandidate = connectionCandidate
                val refreshRetry =
                    existingCandidate != null &&
                        reconnectAttempt > 0 &&
                        SystemClock.elapsedRealtime() >= directoryRefreshAfterElapsedMs &&
                        !directoryClient.hasFreshCache()
                val primaryCandidate =
                    if (existingCandidate == null || refreshRetry) {
                        directoryRefreshAfterElapsedMs = SystemClock.elapsedRealtime() + 60_000L
                        resolveConnectCandidate(proxyStateStore, existingCandidate).also {
                            connectionCandidate = it
                        }
                    } else existingCandidate
                ensureGenerationCurrent(myGeneration)

                val socksPort = tunnelSettings.socksPort
                ListenerAvailability.requireAvailable(
                    listOf(socksPort, SettingsStore.CONTROLLER_PORT)
                )
                val socksBindAddress = tunnelSettings.socksBindAddress

                val proxyOnlyMode = tunnelSettings.proxyOnlyMode
                nativeProtectionRequired = !proxyOnlyMode
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAlwaysOn && proxyOnlyMode) {
                    throw IllegalArgumentException(
                        "Proxy-only mode cannot satisfy Android always-on VPN"
                    )
                }
                _proxyOnlyActive.value = proxyOnlyMode

                fun connectedLabel(): String =
                    if (proxyOnlyMode) {
                        "Proxy active \u2022 $socksBindAddress:$socksPort"
                    } else {
                        val name =
                            if (primaryCandidate.countryCode.equals(RECOMMENDED_COUNTRY_CODE, ignoreCase = true) ||
                                primaryCandidate.countryName.equals("Recomended", ignoreCase = true)
                            ) {
                                "Recommended"
                            } else {
                                primaryCandidate.countryName.ifBlank { primaryCandidate.countryCode }
                            }
                        "Connected \u2022 $name"
                    }

                if (proxyOnlyMode) {
                    ControlPlaneHttp.socketProtector = null
                } else {
                    ControlPlaneHttp.socketProtector = { socket -> protect(socket) }
                }

                ControlPlaneHttp.networkProvider = { firstUsableNetwork() }

                suspend fun mintProxyPass(): ProxyPass {
                    var auth = app.authRepository.ensureFreshAccessToken()
                    if (tokenStore.sessionEpoch != sessionEpoch)
                        throw TokenInvalidError("Session changed")
                    val guardian = GuardianClient()
                    val pass =
                        try {
                            guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
                        } catch (quota: QuotaExceededError) {
                            settingsStore.saveAccountQuota(
                                false,
                                quota.quotaRemaining,
                                quota.quotaMax,
                            )
                            throw quota
                        } catch (rejected: TokenInvalidError) {
                            AppLogger.w(
                                TAG,
                                "Guardian rejected the OAuth access token; renewing it and retrying",
                                rejected,
                            )
                            val renewed = app.authRepository.refreshAccessToken()
                            if (tokenStore.sessionEpoch != sessionEpoch)
                                throw TokenInvalidError("Session changed")
                            if (renewed != null) {
                                auth = renewed
                                try {
                                    guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
                                } catch (stillRejected: TokenInvalidError) {
                                    AppLogger.w(
                                        TAG,
                                        "still rejected; activating the Guardian entitlement and retrying",
                                        stillRejected,
                                    )
                                    guardian.activateGuardian(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
                                    guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
                                }
                            } else {
                                AppLogger.w(TAG, "token renewal failed; activating Guardian entitlement and retrying")
                                guardian.activateGuardian(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
                                guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
                            }
                        }
                    val hasQuota = pass.quotaRemaining != null || pass.quotaMax != null
                    val isUnlimited = if (hasQuota) false else settingsStore.cachedIsUnlimited
                    settingsStore.saveAccountQuota(
                        isUnlimited,
                        pass.quotaRemaining,
                        pass.quotaMax,
                    )
                    return pass
                }

                val pass = mintProxyPass()
                validateProxyPass(pass)
                val lifetimeNote =
                    pass.expiresAtEpochSeconds?.let { expiresAt ->
                        val seconds = (expiresAt * 1_000L - System.currentTimeMillis()) / 1_000L
                        " (valid for ${seconds}s)"
                    } ?: " (lifetime not stated)"
                AppLogger.i(TAG, "connect: acquired Guardian proxy pass$lifetimeNote")
                val initialDeadline = proxyPassDeadline(pass.expiresAtEpochSeconds)
                tokenStore.withCurrentSession(sessionEpoch) {
                    synchronized(credentialCommitLock) {
                        ensureGenerationCurrent(myGeneration)
                        Libbox.setBearerToken(pass.token)
                        installedCredentialDeadline.set(initialDeadline)
                    }
                }

                startCredentialExpiryWatchdog(myGeneration)

                val configContent =
                    SingBoxConfigBuilder.build(
                        primaryCandidate = primaryCandidate,
                        settings = tunnelSettings,
                        bearerToken = pass.token,
                        appPackageName = packageName,
                        controllerSecret = controllerSecret,
                    )
                ensureGenerationCurrent(myGeneration)
                ensureLibboxSetup()
                try {
                    Libbox.checkConfig(configContent)
                } catch (error: Exception) {
                    throw IllegalArgumentException(
                        "Invalid VPN configuration: ${error.message}",
                        error,
                    )
                }
                AppLogger.d(TAG, "sing-box config validated successfully")

                ensureGenerationCurrent(myGeneration)
                val platformInterface =
                    FoxyPlatformInterface(
                        this,
                        includeOnly =
                            tunnelSettings.splitTunnelMode == SettingsStore.SplitTunnelMode.INCLUDE,
                    ) {
                        tunnelInterfaceActive = true
                        reportedUnderlying = null
                        runCatching {
                            firstUsableNetwork()?.let { underlying ->
                                reportUnderlyingNetwork(underlying)
                            }
                        }
                    }
                activePlatformInterface = platformInterface

                ensureLibboxSetup()
                runCatching { File(filesDir, "command.sock").delete() }
                runCatching { File(filesDir, "cache.db").delete() }
                val server = CommandServer(platformInterface, platformInterface)
                commandServer = server
                server.start()
                ensureGenerationCurrent(myGeneration)
                server.startOrReloadService(configContent, OverrideOptions())

                ensureGenerationCurrent(myGeneration)

                val connectedText = connectedLabel()
                tokenStore.withCurrentSession(sessionEpoch) {
                    synchronized(credentialCommitLock) {
                        ensureGenerationCurrent(myGeneration)
                        validateProxyPass(pass)
                        _connectedAtElapsedMs.value = SystemClock.elapsedRealtime()
                        _lastError.value = null
                        _activeCandidate.value = primaryCandidate
                        _tunnelHealth.value = TunnelHealth.CHECKING
                        _state.value = ConnectionState.CONNECTED
                    }
                }
                reconnectAttempt = 0
                healthRecoveryInProgress = false
                retryNotBeforeElapsedMs.set(0L)
                statusLabel = connectedText
                updateNotification(connectedText)
                AppLogger.i(
                    TAG,
                    "connect: CONNECTED via sing-box core to ${primaryCandidate.authority}",
                )

                val proxyCredentials =
                    if (tunnelSettings.localProxyAuthEnabled)
                        tunnelSettings.localProxyUsername to tunnelSettings.localProxyPassword
                    else "" to ""
                activeProxyCredentials = proxyCredentials
                activeSocksPort = socksPort
                pingJob = scope.launch { measureActiveLatency() }

                startProxyPassRenewal(
                    generation = myGeneration,
                    initialDeadline = initialDeadline,
                    sessionEpoch = sessionEpoch,
                ) {
                    mintProxyPass()
                }

                exitCheckJob =
                    scope.launch {
                        val exitCheck = ExitCheck()
                        repeat(3) { attempt ->
                            if (!isCurrentSession(myGeneration)) return@launch
                            val result =
                                exitCheck.fetchProxiedIpInfo(
                                    socksPort,
                                    proxyCredentials.first,
                                    proxyCredentials.second,
                                )
                            val info = result.getOrNull()
                            if (info != null) {
                                synchronized(credentialCommitLock) {
                                    if (
                                        isActive &&
                                            isCurrentSession(myGeneration) &&
                                            _activeCandidate.value == primaryCandidate
                                    ) {
                                        _vpnEdgeIp.value = info
                                        AppLogger.i(
                                            TAG,
                                            "exit check: observed ip=${info.ip}, country=${info.countryCode}",
                                        )
                                    }
                                }
                                return@launch
                            }
                            AppLogger.w(
                                TAG,
                                "exit check attempt ${attempt + 1} failed (non-fatal)",
                                result.exceptionOrNull(),
                            )
                            if (attempt < 2) delay(2_000L * (attempt + 1))
                        }
                    }

                startSpeedUpdates(myGeneration)
                startHealthMonitor(myGeneration, tunnelSettings, primaryCandidate)
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                synchronized(credentialCommitLock) { Libbox.setBearerToken("") }
                releaseResources(detachResources())
                ensureGenerationCurrent(myGeneration)
                AppLogger.e(TAG, "connect failed", error)
                val failedAutomaticRecovery = healthRecoveryInProgress
                healthRecoveryInProgress = false
                _lastError.value =
                    if (failedAutomaticRecovery) "Automatic recovery failed. Reconnect to retry."
                    else friendlyErrorMessage(error)

                if (
                    !failedAutomaticRecovery &&
                        !isFatalUpstreamError(error) &&
                        _state.value != ConnectionState.DISCONNECTED
                ) {
                    reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
                    val retryDelay =
                        retryDelayMillis(
                            minOf(60_000L, 1_000L shl reconnectAttempt),
                            (error as? GuardianHttpError)?.retryAfterMs,
                            kotlin.random.Random.nextLong(250L, 1_000L),
                        )
                    val nowElapsed = SystemClock.elapsedRealtime()
                    retryNotBeforeElapsedMs.set(retryDeadlineMillis(nowElapsed, retryDelay))
                    AppLogger.w(
                        TAG,
                        "transient connect failure; retrying in ${retryDelay / 1_000L}s",
                    )
                    _state.value = ConnectionState.CONNECTING
                    statusLabel = "Reconnecting\u2026"
                    updateNotification(statusLabel)
                    connectJob = null
                    scheduleReconnect(retryDelay)
                    return
                }
                requestDisconnect("connection failed")
            }
        connectJob = null
    }

    private fun scheduleReconnect(delayMillis: Long) {
        if (retryJob?.isActive == true || stopping) return
        retryJob =
            scope.launch {
                try {
                    delay(delayMillis)
                    if (
                        !stopping &&
                            _state.value == ConnectionState.CONNECTING &&
                            connectJob == null
                    ) {
                        connectJob = scope.launch { opMutex.withLock { connect() } }
                    }
                } finally {
                    retryJob = null
                }
            }
    }

    private suspend fun resolveConnectCandidate(
        proxyStateStore: ProxyStateStore,
        current: ProxyCandidate? = null,
    ): ProxyCandidate {
        val saved = current ?: proxyStateStore.load()
        val client =
            (applicationContext as? FoxyVpnApp)?.serverListClient
                ?: ServerListClient(applicationContext)
        val cached = client.loadCachedCountries()
        val countries =
            if (cached.isNotEmpty() && client.hasFreshCache()) cached
            else
                runCatching {
                        withTimeout(SERVER_LIST_FETCH_TIMEOUT_MS) { client.fetchCountries() }
                    }
                    .getOrElse {
                        if (it is CancellationException && it !is TimeoutCancellationException)
                            throw it
                        AppLogger.w(
                            TAG,
                            "could not refresh server directory; using cached selection",
                            it,
                        )
                        cached
                    }

        if (countries.isEmpty() && saved != null) return saved
        val candidates =
            countries.flatMap { ServerListClient.candidatesForCountry(countries, it.code) }
        val chosen =
            com.vauth.foxyvpn.data.preferredConnectionCandidates(candidates, saved).randomOrNull()
                ?: throw IllegalArgumentException(
                    if (saved == null) "No servers are available. Choose a location first."
                    else "No available servers in the selected location. Choose another location."
                )

        if (current == null) proxyStateStore.saveIfUnchanged(saved, chosen)
        return chosen
    }

    private fun friendlyErrorMessage(error: Throwable): String =
        when (error) {
            is QuotaExceededError -> "Your VPN quota is exhausted. Try again later."
            is TokenInvalidError -> "Your session was rejected. Please sign in again."
            is GuardianHttpError ->
                if (error.statusCode in listOf(401, 403)) {
                    "Your session was rejected. Please sign in again."
                } else error.message ?: "Guardian is temporarily unavailable"
            is TimeoutCancellationException -> "the connection timed out"
            else -> error.message ?: (error::class.simpleName ?: "Connection failed")
        }

    private fun teardown() {
        retryJob?.cancel()
        retryJob = null
        connectJob?.cancel()
        connectJob = null
        val ownsCore =
            synchronized(credentialCommitLock) {
                if (coreOwner === this) {
                    Libbox.setBearerToken("")
                    coreOwner = null
                    true
                } else false
            }
        releaseResources(detachResources())
        if (!ownsCore) return
        ControlPlaneHttp.socketProtector = null
        ControlPlaneHttp.networkProvider = null
        _state.value = ConnectionState.DISCONNECTED
        _vpnLiveStats.value = VpnLiveStats()
        _connectedAtElapsedMs.value = null
        _pingLatencyMs.value = null
        _vpnEdgeIp.value = null
        _activeCandidate.value = null
        _tunnelHealth.value = TunnelHealth.UNKNOWN
        connectionSettings = null
        connectionCandidate = null
        healthRecoveryInProgress = false
        QuickConnectWidgetProvider.updateAll(this, ConnectionState.DISCONNECTED)
    }

    private fun detachResources(): SessionResources {
        credentialExpiryJob?.cancel()
        credentialExpiryJob = null
        activeSocksPort = null
        activeProxyCredentials = "" to ""
        pingJob?.cancel()
        pingJob = null
        exitCheckJob?.cancel()
        exitCheckJob = null
        healthJob?.cancel()
        healthJob = null
        trafficMonitor?.stop()
        trafficMonitor = null

        tokenRenewalJob?.cancel()
        tokenRenewalJob = null
        val detached = SessionResources(commandServer, activePlatformInterface)
        tunnelInterfaceActive = false
        commandServer = null
        activePlatformInterface = null
        return detached
    }

    private fun releaseResources(resources: SessionResources) {
        if (resources.isEmpty) return
        runCatching { resources.commandServer?.closeService() }
            .onFailure { AppLogger.w(TAG, "error closing sing-box service", it) }
        runCatching { resources.commandServer?.close() }
            .onFailure { AppLogger.w(TAG, "error closing sing-box command server", it) }
        runCatching { resources.platformInterface?.closeTun() }
            .onFailure { AppLogger.w(TAG, "error closing VPN interface", it) }
    }

    override fun onRevoke() {
        requestDisconnect("VPN permission was revoked by the system")
        super.onRevoke()
    }

    private fun isCurrentSession(generation: Int): Boolean =
        !stopping &&
            coreOwner === this &&
            generation == connectionGeneration.get() &&
            _state.value == ConnectionState.CONNECTED

    private suspend fun measureActiveLatency(): Int? {

        val generation = connectionGeneration.get()
        val port = activeSocksPort ?: return null
        return pingMutex.withLock {
            fun current() = isCurrentSession(generation) && activeSocksPort == port
            if (!current()) return@withLock null
            val url = (applicationContext as FoxyVpnApp).settingsStore.latencyProbeUrl
            val credentials = activeProxyCredentials
            val latency =
                PingUtil.measureProxyLatencyMs(port, url, credentials.first, credentials.second)
            synchronized(credentialCommitLock) {
                if (current()) {
                    _pingLatencyMs.value = latency
                    latency
                } else null
            }
        }
    }

    private fun startHealthMonitor(
        generation: Int,
        settings: TunnelSettings,
        candidate: ProxyCandidate,
    ) {
        healthJob?.cancel()
        healthJob =
            scope.launch {
                var failures = 0
                val credentials = activeProxyCredentials
                val urls =
                    listOf(
                        "https://www.gstatic.com/generate_204",
                        "https://www.cloudflare.com/cdn-cgi/trace",
                    )
                fun current() = isActive && isCurrentSession(generation)
                while (current()) {
                    val network = firstUsableNetwork()
                    val capabilities =
                        network?.let {
                            getSystemService(ConnectivityManager::class.java)
                                ?.getNetworkCapabilities(it)
                        }
                    if (
                        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) !=
                            true
                    ) {
                        synchronized(credentialCommitLock) {
                            if (current()) _tunnelHealth.value = TunnelHealth.NO_NETWORK
                        }
                        failures = 0
                        delay(60_000L)
                        continue
                    }
                    val healthy =
                        urls.any {
                            PingUtil.measureProxyLatencyMs(
                                settings.socksPort,
                                it,
                                credentials.first,
                                credentials.second,
                            ) != null
                        }
                    synchronized(credentialCommitLock) {
                        if (!current()) return@launch
                        _tunnelHealth.value =
                            if (healthy) TunnelHealth.HEALTHY else TunnelHealth.UNREACHABLE
                    }
                    failures = if (healthy) 0 else failures + 1
                    if (
                        failures >= 3 &&
                            settings.automaticRecovery &&
                            settings.effectiveCustomEdgeAddress == null
                    ) {

                        val directWorks =
                            urls.all { url ->
                                try {
                                    val request = okhttp3.Request.Builder().url(url).build()
                                    ControlPlaneHttp.client.newCall(request).awaitResponse().use {
                                        it.isSuccessful
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    false
                                }
                            }
                        if (
                            directWorks &&
                                current() &&
                                firstUsableNetwork() == network &&
                                recoveryBudget.tryAcquire(SystemClock.elapsedRealtime())
                        ) {
                            val directory = (applicationContext as FoxyVpnApp).serverListClient
                            val cached = directory.loadCachedCountries()
                            val countries =
                                if (cached.isNotEmpty() && directory.hasFreshCache()) cached
                                else
                                    try {
                                        withTimeout(SERVER_LIST_FETCH_TIMEOUT_MS) {
                                            directory.fetchCountries()
                                        }
                                    } catch (cancelled: CancellationException) {
                                        if (cancelled !is TimeoutCancellationException)
                                            throw cancelled
                                        cached
                                    } catch (_: Exception) {
                                        cached
                                    }
                            if (!current() || firstUsableNetwork() != network) return@launch
                            val alternate =
                                ServerListClient.candidatesForCountry(
                                        countries,
                                        candidate.countryCode,
                                    )
                                    .filter { it.authority != candidate.authority }
                                    .randomOrNull()
                            AppLogger.w(
                                TAG,
                                "confirmed proxy-path failure; bounded recovery within the selected country",
                            )

                            connectJob =
                                scope.launch {
                                    opMutex.withLock {
                                        synchronized(credentialCommitLock) {
                                            if (
                                                stopping ||
                                                    generation != connectionGeneration.get() ||
                                                    coreOwner !== this@FoxyVpnService
                                            )
                                                return@withLock
                                            Libbox.setBearerToken("")
                                            _state.value = ConnectionState.CONNECTING
                                            _activeCandidate.value = null
                                            _vpnEdgeIp.value = null
                                            _pingLatencyMs.value = null
                                            _vpnLiveStats.value = VpnLiveStats()
                                            _connectedAtElapsedMs.value = null
                                            _tunnelHealth.value = TunnelHealth.CHECKING
                                        }
                                        releaseResources(detachResources())
                                        connectionCandidate = alternate ?: candidate
                                        statusLabel = "Reconnecting…"
                                        updateNotification(statusLabel)
                                        healthRecoveryInProgress = true
                                        connect()
                                    }
                                }
                            return@launch
                        }
                    }
                    delay(60_000L)
                }
            }
    }

    private fun startSpeedUpdates(generation: Int) {
        trafficMonitor?.stop()
        trafficMonitor =
            NativeTrafficMonitor(scope) { stats ->
                    synchronized(credentialCommitLock) {
                        if (isCurrentSession(generation)) {
                            _vpnLiveStats.value = stats
                            updateNotification(
                                statusLabel,
                                "\u2193 ${formatBytesPerSecond(stats.rxRateBytesPerSec)}  \u2191 ${formatBytesPerSecond(stats.txRateBytesPerSec)}",
                            )
                        }
                    }
                }
                .also { it.start() }
    }

    private fun buildNotification(text: String, subText: String? = null): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                REQUEST_CODE_OPEN,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val disconnectIntent =
            PendingIntent.getService(
                this,
                REQUEST_CODE_DISCONNECT,
                Intent(this, FoxyVpnService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder =
            NotificationCompat.Builder(this, FoxyVpnApp.VPN_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("FoxyVPN")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Disconnect",
                    disconnectIntent,
                )
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)

        if (subText != null) {
            builder.setSubText(subText)
        }
        return builder.build()
    }

    private fun enterForeground(text: String) {
        val notification = buildNotification(text)
        lastNotificationText = text
        lastNotificationSubText = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundActive = true
    }

    private fun exitForeground() {
        if (!foregroundActive) return
        foregroundActive = false
        lastNotificationText = null
        lastNotificationSubText = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun updateNotification(text: String, subText: String? = null) {
        if (!foregroundActive) return
        if (lastNotificationText == text && lastNotificationSubText == subText) return
        lastNotificationText = text
        lastNotificationSubText = subText
        val notification = buildNotification(text, subText)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private val recoveryBudget = RecoveryBudget()
        private val nativeMutex = Mutex()
        private val credentialCommitLock = Any()
        @Volatile private var coreOwner: FoxyVpnService? = null
        const val ACTION_CONNECT = "com.vauth.foxyvpn.action.CONNECT"
        const val ACTION_DISCONNECT = "com.vauth.foxyvpn.action.DISCONNECT"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_CODE_OPEN = 0
        private const val REQUEST_CODE_DISCONNECT = 1

        private val _proxyOnlyActive = MutableStateFlow(false)
        val proxyOnlyActive: StateFlow<Boolean> = _proxyOnlyActive

        private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
        val state: StateFlow<ConnectionState> = _state

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError

        private val _vpnLiveStats = MutableStateFlow(VpnLiveStats())
        val vpnLiveStats: StateFlow<VpnLiveStats> = _vpnLiveStats

        private val _connectedAtElapsedMs = MutableStateFlow<Long?>(null)
        val connectedAtElapsedMs: StateFlow<Long?> = _connectedAtElapsedMs

        private val _pingLatencyMs = MutableStateFlow<Int?>(null)
        val pingLatencyMs: StateFlow<Int?> = _pingLatencyMs

        private val _activeCandidate = MutableStateFlow<ProxyCandidate?>(null)
        val activeCandidate: StateFlow<ProxyCandidate?> = _activeCandidate
        private val _tunnelHealth = MutableStateFlow(TunnelHealth.UNKNOWN)
        val tunnelHealth: StateFlow<TunnelHealth> = _tunnelHealth

        private val _vpnEdgeIp = MutableStateFlow<IpLocationInfo?>(null)
        val vpnEdgeIp: StateFlow<IpLocationInfo?> = _vpnEdgeIp

        suspend fun pingNow(): Int? = coreOwner?.measureActiveLatency()

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, FoxyVpnService::class.java).setAction(ACTION_CONNECT)
            )
        }

        fun stop(context: Context) {
            val intent = Intent(context, FoxyVpnService::class.java).setAction(ACTION_DISCONNECT)
            runCatching { context.startService(intent) }
                .onFailure {
                    AppLogger.w(
                        TAG,
                        "could not deliver the disconnect intent; service is not running",
                        it,
                    )
                }
        }
    }
}
