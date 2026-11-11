package com.vauth.foxyvpn.data

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject

data class ComponentConfig(
    val headers: Map<String, String>,
    val protocols: List<Protocol> = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1),
)

data class GuardianEndpoints(
    val token: String = "https://vpn.mozilla.org/api/v1/fpn/token",
    val status: String = "https://vpn.mozilla.org/api/v1/fpn/status",
    val activate: String = "https://vpn.mozilla.org/api/v1/fpn/activate",
)

data class ControlPlaneEndpoints(
    val oauthToken: String = "https://oauth.accounts.firefox.com/v1/token",
    val oauthFallback: String = "https://api.accounts.firefox.com/v1/oauth/token",
    val authorization: String = "https://api.accounts.firefox.com/v1/oauth/authorization",
    val recoveryStatus: String = "https://api.accounts.firefox.com/v1/recovery_email/status",
    val sessionDestroy: String = "https://api.accounts.firefox.com/v1/session/destroy",
    val login: String = "https://accounts.firefox.com/?context=fx_desktop_v3&service=sync&action=email",
)

data class EndpointsConfig(
    val guardian: GuardianEndpoints = GuardianEndpoints(),
    val controlPlane: ControlPlaneEndpoints = ControlPlaneEndpoints(),
    val serverList: String = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records",
    val productDetails: String = "https://product-details.mozilla.org/1.0/firefox_versions.json",
    val exitCheck: String = "https://www.cloudflare.com/cdn-cgi/trace",
    val latencyProbe: String = "https://www.gstatic.com/generate_204",
)

val DEFAULT_RELAYERS: Map<String, String> = emptyMap()
val DEFAULT_RELAYER_ROUTES: Set<String> = setOf("guardian")
val DEFAULT_FAILOVER_CODES: Set<Int> = setOf(451)

data class RemoteConfig(
    val version: Int,
    val endpoints: EndpointsConfig = EndpointsConfig(),
    val relayerRoutes: Set<String> = DEFAULT_RELAYER_ROUTES,
    val relayers: Map<String, String> = DEFAULT_RELAYERS,
    val failoverCodes: Set<Int> = DEFAULT_FAILOVER_CODES,
    val controlPlane: ComponentConfig,
    val guardian: ComponentConfig,
    val solver: ComponentConfig,
    val browser: ComponentConfig,
)

internal val ANDROID_RELEASE: String =
    Build.VERSION.RELEASE?.trim()?.takeIf { it.isNotEmpty() } ?: "14"

internal val ANDROID_SDK: String =
    Build.VERSION.SDK_INT.toString()

internal fun resolvePlaceholders(raw: String): String =
    raw.replace("\${android_version}", ANDROID_RELEASE, ignoreCase = true)
        .replace("{android_version}", ANDROID_RELEASE, ignoreCase = true)
        .replace("\${os_version}", ANDROID_RELEASE, ignoreCase = true)
        .replace("{os_version}", ANDROID_RELEASE, ignoreCase = true)
        .replace("\${android_sdk}", ANDROID_SDK, ignoreCase = true)
        .replace("{android_sdk}", ANDROID_SDK, ignoreCase = true)

private val DEFAULT_CONTROL_PLANE =
    ComponentConfig(
        headers =
            mapOf(
                "User-Agent" to resolvePlaceholders("MozillaVPN/2.24.0 (sys:android {android_version})"),
                "Accept" to "*/*",
                "Accept-Encoding" to "identity",
            ),
        protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1),
    )

private val DEFAULT_GUARDIAN =
    ComponentConfig(
        headers =
            mapOf(
                "User-Agent" to resolvePlaceholders("MozillaVPN/2.24.0 (sys:android {android_version})"),
                "Accept" to "application/json",
                "Accept-Encoding" to "identity",
                "Content-Type" to "application/json",
                "Cache-Control" to "max-age=0",
            ),
    )

private val DEFAULT_SOLVER =
    ComponentConfig(
        headers =
            mapOf(
                "User-Agent" to resolvePlaceholders("MozillaVPN/2.24.0 (sys:android {android_version})"),
                "Accept-Encoding" to "identity",
            ),
    )

private val DEFAULT_BROWSER =
    ComponentConfig(
        headers =
            mapOf(
                "User-Agent" to
                    resolvePlaceholders(
                        "Mozilla/5.0 (Android {android_version}; Mobile; rv:159.0) Gecko/159.0 Firefox/159.0"
                    ),
            ),
    )

val DEFAULT_REMOTE_CONFIG =
    RemoteConfig(
        version = 1,
        endpoints = EndpointsConfig(),
        relayerRoutes = DEFAULT_RELAYER_ROUTES,
        relayers = DEFAULT_RELAYERS,
        failoverCodes = DEFAULT_FAILOVER_CODES,
        controlPlane = DEFAULT_CONTROL_PLANE,
        guardian = DEFAULT_GUARDIAN,
        solver = DEFAULT_SOLVER,
        browser = DEFAULT_BROWSER,
    )

object FoxyRemoteConfig {
    private const val TAG = "FoxyRemoteConfig"
    private const val FILE_NAME = "foxy_config.json"
    private const val PRIMARY_URL =
        "https://raw.githubusercontent.com/Vauth/FoxyConfig/main/config.json"
    private const val FALLBACK_URL =
        "https://cdn.jsdelivr.net/gh/Vauth/FoxyConfig@main/config.json"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val refreshMutex = Mutex()
    private val isRefreshing = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var current: RemoteConfig = DEFAULT_REMOTE_CONFIG
        private set

    @Volatile
    private var lastRawJson: String = ""

    fun resolvePlaceholders(raw: String): String = com.vauth.foxyvpn.data.resolvePlaceholders(raw)

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val file = File(app.filesDir, FILE_NAME)
        if (file.isFile && file.length() > 0) {
            val text = runCatching { file.readText() }.getOrNull().orEmpty()
            val parsed = parseConfig(text)
            if (parsed != null) {
                current = parsed
                lastRawJson = text
                AppLogger.i(TAG, "loaded cached config (version ${parsed.version})")
            } else {
                AppLogger.w(TAG, "could not parse cached config; using defaults")
            }
        }
    }

    fun triggerRefresh(client: OkHttpClient = ControlPlaneHttp.client) {
        val context = appContext ?: return
        if (!isRefreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                refresh(context, client)
            } finally {
                isRefreshing.set(false)
            }
        }
    }

    suspend fun refresh(context: Context, client: OkHttpClient = ControlPlaneHttp.client) {
        withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                val fetchedJson =
                    fetchRemoteJson(client, PRIMARY_URL)
                        ?: fetchRemoteJson(client, FALLBACK_URL)
                        ?: run {
                            AppLogger.w(TAG, "could not fetch remote config from primary or fallback")
                            return@withContext
                        }

                if (fetchedJson.trim() == lastRawJson.trim()) {
                    AppLogger.d(TAG, "remote config is up to date (version ${current.version})")
                    return@withContext
                }

                val parsed = parseConfig(fetchedJson)
                if (parsed == null) {
                    AppLogger.w(TAG, "could not parse fetched remote config")
                    return@withContext
                }

                val file = File(context.filesDir, FILE_NAME)
                val tempFile = File(context.filesDir, "$FILE_NAME.tmp")
                val writeSuccess =
                    runCatching {
                        tempFile.writeText(fetchedJson)
                        if (tempFile.renameTo(file)) {
                            true
                        } else {
                            tempFile.copyTo(file, overwrite = true)
                            tempFile.delete()
                            true
                        }
                    }.getOrDefault(false)

                if (writeSuccess) {
                    current = parsed
                    lastRawJson = fetchedJson
                    AppLogger.i(TAG, "config updated to version ${parsed.version}")
                } else {
                    AppLogger.w(TAG, "failed to persist remote config to disk")
                }
            }
        }
    }

    private suspend fun fetchRemoteJson(client: OkHttpClient, url: String): String? {
        return try {
            withTimeoutOrNull(8_000L) {
                val request =
                    Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .header("Cache-Control", "no-cache")
                        .build()
                client.newCall(request).awaitResponse().use { response ->
                    if (!response.isSuccessful) {
                        AppLogger.w(TAG, "fetching $url returned HTTP ${response.code}")
                        return@withTimeoutOrNull null
                    }
                    val text = response.body?.readBoundedText(128 * 1024).orEmpty().trim()
                    if (text.startsWith("{") && text.endsWith("}")) text else null
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            AppLogger.w(TAG, "failed to fetch config from $url: ${e.message}")
            null
        }
    }

    private fun parseConfig(jsonString: String): RemoteConfig? {
        return runCatching {
            val root = JSONObject(jsonString)
            val version = root.optInt("version", 1)

            fun parseEndpoints(): EndpointsConfig {
                val defaultEndpoints = EndpointsConfig()
                val endpointsObj = root.optJSONObject("endpoints") ?: return defaultEndpoints
                val guardianObj = endpointsObj.optJSONObject("guardian")
                val guardian =
                    if (guardianObj != null) {
                        val d = defaultEndpoints.guardian
                        GuardianEndpoints(
                            token = guardianObj.optString("token", "").ifBlank { d.token },
                            status = guardianObj.optString("status", "").ifBlank { d.status },
                            activate = guardianObj.optString("activate", "").ifBlank { d.activate },
                        )
                    } else defaultEndpoints.guardian

                val cpObj = endpointsObj.optJSONObject("controlPlane")
                val controlPlane =
                    if (cpObj != null) {
                        val d = defaultEndpoints.controlPlane
                        ControlPlaneEndpoints(
                            oauthToken = cpObj.optString("oauthToken", "").ifBlank { d.oauthToken },
                            oauthFallback = cpObj.optString("oauthFallback", "").ifBlank { d.oauthFallback },
                            authorization = cpObj.optString("authorization", "").ifBlank { d.authorization },
                            recoveryStatus = cpObj.optString("recoveryStatus", "").ifBlank { d.recoveryStatus },
                            sessionDestroy = cpObj.optString("sessionDestroy", "").ifBlank { d.sessionDestroy },
                            login = cpObj.optString("login", "").ifBlank { d.login },
                        )
                    } else defaultEndpoints.controlPlane

                return EndpointsConfig(
                    guardian = guardian,
                    controlPlane = controlPlane,
                    serverList = endpointsObj.optString("serverList", "").ifBlank { defaultEndpoints.serverList },
                    productDetails = endpointsObj.optString("productDetails", "").ifBlank { defaultEndpoints.productDetails },
                    exitCheck = endpointsObj.optString("exitCheck", "").ifBlank { defaultEndpoints.exitCheck },
                    latencyProbe = endpointsObj.optString("latencyProbe", "").ifBlank { defaultEndpoints.latencyProbe },
                )
            }

            fun parseComponent(name: String, default: ComponentConfig): ComponentConfig {
                val obj = root.optJSONObject(name) ?: return default
                val headersObj = obj.optJSONObject("headers")
                val headers =
                    if (headersObj != null) {
                        val map = mutableMapOf<String, String>()
                        for (key in headersObj.keys()) {
                            val value = headersObj.optString(key, "")
                            if (value.isNotEmpty()) {
                                map[key] = resolvePlaceholders(value)
                            }
                        }
                        if (map.isNotEmpty()) map else default.headers
                    } else default.headers

                val settingsObj = obj.optJSONObject("settings")
                val protocols =
                    if (settingsObj != null && settingsObj.has("protocols")) {
                        val arr = settingsObj.optJSONArray("protocols")
                        if (arr != null && arr.length() > 0) {
                            val list = mutableListOf<Protocol>()
                            for (i in 0 until arr.length()) {
                                when (arr.optString(i).uppercase()) {
                                    "HTTP_2", "H2" -> list.add(Protocol.HTTP_2)
                                    "HTTP_1_1", "HTTP1.1" -> list.add(Protocol.HTTP_1_1)
                                }
                            }
                            if (list.isNotEmpty()) list else default.protocols
                        } else default.protocols
                    } else default.protocols

                return ComponentConfig(headers = headers, protocols = protocols)
            }

            fun parseRelayers(): Map<String, String> {
                val obj = root.optJSONObject("relayers") ?: return emptyMap()
                val result = mutableMapOf<String, String>()
                for (key in obj.keys()) {
                    val raw = obj.optString(key, "").trim()
                    if (raw.isNotEmpty()) {
                        val url =
                            if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
                                raw
                            } else {
                                "https://$raw"
                            }
                        result[key] = url
                    }
                }
                return result
            }

            fun parseRelayerRoutes(): Set<String> {
                val arr = root.optJSONArray("relayerRoutes") ?: return DEFAULT_RELAYER_ROUTES
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i, "").trim().lowercase()
                    if (item.isNotEmpty()) {
                        set.add(item)
                    }
                }
                return if (set.isNotEmpty()) set else DEFAULT_RELAYER_ROUTES
            }

            fun parseFailoverCodes(): Set<Int> {
                val arr = root.optJSONArray("failoverCodes") ?: return DEFAULT_FAILOVER_CODES
                val set = mutableSetOf<Int>()
                for (i in 0 until arr.length()) {
                    val code = arr.optInt(i, -1)
                    if (code in 100..599) {
                        set.add(code)
                    }
                }
                return if (set.isNotEmpty()) set else DEFAULT_FAILOVER_CODES
            }

            RemoteConfig(
                version = version,
                endpoints = parseEndpoints(),
                relayerRoutes = parseRelayerRoutes(),
                relayers = parseRelayers(),
                failoverCodes = parseFailoverCodes(),
                controlPlane = parseComponent("controlPlane", DEFAULT_CONTROL_PLANE),
                guardian = parseComponent("guardian", DEFAULT_GUARDIAN),
                solver = parseComponent("solver", DEFAULT_SOLVER),
                browser = parseComponent("browser", DEFAULT_BROWSER),
            )
        }.getOrNull()
    }
}
