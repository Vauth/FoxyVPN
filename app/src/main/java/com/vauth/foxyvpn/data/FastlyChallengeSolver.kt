package com.vauth.foxyvpn.data

import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class FastlyChallengeError(message: String) : Exception(message)

private const val SOLVE_TIMEOUT_SECONDS = 60L

private const val MAX_POST_BACK_ROUNDS = 8

private const val POW_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

private const val HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

private val CHALLENGE_PREFIX_REGEX = Regex("/_fs-ch-[A-Za-z0-9]+")
private val INIT_CALL_REGEX = Regex("\\binit\\s*\\(\\s*\\[")

class FastlyChallengeSolver(private val cookieJar: SimpleCookieJar) {

    private companion object {
        val solverMutex = Mutex()
    }

    private fun allowedHosts(): Set<String> {
        val hosts = mutableSetOf(
            "vpn.mozilla.org",
            "api.accounts.firefox.com",
            "oauth.accounts.firefox.com",
            "accounts.firefox.com",
        )
        val endpoints = FoxyRemoteConfig.current.endpoints
        val urls = listOf(
            endpoints.controlPlane.login,
            endpoints.controlPlane.recoveryStatus,
            endpoints.controlPlane.sessionDestroy,
            endpoints.controlPlane.oauthToken,
            endpoints.controlPlane.oauthFallback,
            endpoints.controlPlane.authorization,
            endpoints.guardian.token,
            endpoints.guardian.status,
            endpoints.guardian.activate,
            endpoints.serverList,
            endpoints.productDetails,
        )
        for (url in urls) {
            runCatching { url.toHttpUrl().host }.getOrNull()?.let { hosts.add(it) }
        }
        return hosts
    }

    suspend fun solveAndInstall(
        challengedBody: String? = null,
        challengedOrigin: String? = null,
        userAgent: String = FirefoxAccountHeaders.controlPlaneUserAgent,
    ) =
        withTimeout(90_000L) {
            solverMutex.withLock {
                withContext(Dispatchers.IO) {
                    val origin =
                        (challengedOrigin ?: "https://api.accounts.firefox.com").toHttpUrl()
                    val trustedHosts = allowedHosts()
                    require(
                        origin.scheme == "https" &&
                            origin.port == 443 &&
                            origin.username.isEmpty() &&
                            origin.password.isEmpty() &&
                            origin.host in trustedHosts
                    ) {
                        "Untrusted challenge origin"
                    }
                    val base = "https://${origin.host}"
                    if (!challengedBody.isNullOrEmpty() && isChallengePage(challengedBody)) {
                        val solverJar = SimpleCookieJar()
                        val solver = newSolverClient(solverJar)
                        solveChallengePage(challengedBody, base, userAgent, solver, solverJar)
                        return@withContext
                    }
                    val defaultBase =
                        runCatching { FoxyRemoteConfig.current.endpoints.controlPlane.oauthToken.toHttpUrl() }
                            .getOrNull()?.let { "https://${it.host}" } ?: "https://api.accounts.firefox.com"
                    val bases =
                        listOf(
                            base,
                            defaultBase,
                            "https://api.accounts.firefox.com",
                            "https://accounts.firefox.com",
                        ).distinct()
                    var lastError: FastlyChallengeError? = null
                    for (candidateBase in bases) {
                        for (candidateUA in listOf(userAgent, FirefoxAccountHeaders.browserUserAgent, FirefoxAccountHeaders.desktopUserAgent).distinct()) {
                            try {
                                if (solveChallengeOnHost(candidateBase, candidateUA)) {
                                    return@withContext
                                }
                            } catch (error: FastlyChallengeError) {
                                lastError = error
                            } catch (e: Exception) {
                                lastError = FastlyChallengeError("Error probing $candidateBase: ${e.message}")
                            }
                        }
                    }
                    throw lastError
                        ?: FastlyChallengeError("host did not serve a Fastly challenge page")
                }
            }
        }

    private fun baseOrigin(prefixUrl: String): String {
        val idx = prefixUrl.indexOf("/_fs-ch-")
        return if (idx > 0) prefixUrl.substring(0, idx) else prefixUrl
    }

    private fun solvePow(base: String, targetHex: String): String? {
        val target =
            try {
                targetHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } catch (e: Exception) {
                return null
            }
        if (target.size != 32) return null
        val digest = MessageDigest.getInstance("SHA-256")
        for (a in POW_ALPHABET) {
            for (b in POW_ALPHABET) {
                val suffix = "$a$b"
                val hash = digest.digest((base + suffix).toByteArray(Charsets.UTF_8))
                if (hash.contentEquals(target)) return suffix
            }
        }
        return null
    }

    private suspend fun fetchText(
        client: OkHttpClient,
        url: String,
        accept: String = HTML_ACCEPT,
        userAgent: String = FirefoxAccountHeaders.controlPlaneUserAgent,
        allowChallenge: Boolean = false,
    ): String {
        val builder = Request.Builder().url(url)
        for ((key, value) in FoxyRemoteConfig.current.solver.headers) {
            builder.header(key, value)
        }
        val request =
            builder
                .header("User-Agent", userAgent)
                .header("Accept", accept)
                .build()
        client.newCall(request).awaitResponse().use { response ->
            val text = response.body?.readBoundedText().orEmpty()
            if (!response.isSuccessful && !(allowChallenge && isChallengePage(text)))
                throw FastlyChallengeError("Challenge page returned HTTP ${response.code}")
            return text
        }
    }

    private fun isChallengePage(text: String): Boolean =
        text.length in 1..2_000_000 &&
            CHALLENGE_PREFIX_REGEX.containsMatchIn(text) &&
            (text.contains("Client Challenge", ignoreCase = true) ||
                text.contains("script.js") ||
                text.contains("fst-post-back"))

    private fun parseChallengeInit(script: String, prefix: String = ""): Pair<JSONArray, String> {
        val matches = INIT_CALL_REGEX.findAll(script).toList().asReversed()
        var legacyResult: Pair<JSONArray, String>? = null

        for (match in matches) {
            val start = match.range.last
            val tokener = JSONTokener(script.substring(start))
            val challenges = runCatching { tokener.nextValue() as? JSONArray }.getOrNull()
            if (challenges == null || challenges.length() == 0 || challenges.length() > 16) continue
            if (tokener.nextClean() != ',') continue
            val token = runCatching { tokener.nextValue() as? String }.getOrNull()
            if (token.isNullOrBlank()) continue

            val delim = tokener.nextClean()
            if (delim == ',') {
                val scriptPrefix = runCatching { tokener.nextValue() as? String }.getOrNull()
                val nextDelim = tokener.nextClean()
                val hasValidDelimiter = nextDelim == ')' || nextDelim == ','
                if (hasValidDelimiter && (prefix.isEmpty() || scriptPrefix == prefix)) {
                    val valid = (0 until challenges.length()).all {
                        val item = challenges.optJSONObject(it)
                        item != null && item.optString("ty").isNotBlank()
                    }
                    if (valid) return challenges to token
                }
            } else if (delim == ')') {
                val valid = (0 until challenges.length()).all {
                    val item = challenges.optJSONObject(it)
                    item != null && item.optString("ty").isNotBlank()
                }
                if (valid && legacyResult == null) {
                    legacyResult = challenges to token
                }
            }
        }

        if (legacyResult != null) return legacyResult

        throw FastlyChallengeError("Could not read the Fastly challenge")
    }

    private suspend fun fetchPat(
        client: OkHttpClient,
        prefixUrl: String,
        token: String,
        userAgent: String,
    ): String {
        val patUrl = "$prefixUrl/pat?token=${URLEncoder.encode(token, "UTF-8")}"
        val builder = Request.Builder().url(patUrl)
        for ((key, value) in FoxyRemoteConfig.current.solver.headers) {
            builder.header(key, value)
        }
        val request =
            builder
                .header("Accept", "text/plain")
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent)
                .header("Origin", baseOrigin(prefixUrl))
                .post(ByteArray(0).toRequestBody(null))
                .build()
        client.newCall(request).awaitResponse().use { response ->
            if (response.code == 400 || response.code == 401) return ""
            if (!response.isSuccessful)
                throw FastlyChallengeError("PAT request returned HTTP ${response.code}")
            val json =
                try {
                    JSONObject(response.body?.readBoundedText().orEmpty())
                } catch (e: Exception) {
                    throw FastlyChallengeError("could not parse PAT response: ${e.message}")
                }
            val auth = json.optString("auth", "")
            if (auth.isEmpty()) throw FastlyChallengeError("empty PAT auth token")
            return auth
        }
    }

    private fun clientMetricsAnswer(): JSONObject =
        JSONObject().apply {
            put("ty", "clientmetrics")
            put("webdriver", false)
            put(
                "bot_detection_result",
                JSONObject().apply {
                    put("bot_detected", false)
                    put("bot_kind", JSONObject.NULL)
                },
            )
            put(
                "browser_metrics",
                JSONObject().apply {
                    put("client_data", "{}")
                    put("error_trace", JSONObject.NULL)
                },
            )
            put("detector_results", JSONObject())
            put("v", 2)
        }

    private suspend fun answerChallenge(
        client: OkHttpClient,
        prefixUrl: String,
        token: String,
        challenge: JSONObject,
        userAgent: String,
    ): JSONObject {
        val data = challenge.optJSONObject("data") ?: JSONObject()
        return when (val type = challenge.optString("ty")) {
            "pow" -> {
                val base = data.optString("base", "")
                val answer =
                    solvePow(base, data.optString("hash", ""))
                        ?: throw FastlyChallengeError(
                            "no proof-of-work solution found for base $base"
                        )
                JSONObject().apply {
                    put("ty", "pow")
                    put("base", base)
                    put("answer", answer)
                    put("hmac", data.optString("hmac", ""))
                    put("expires", data.optString("expires", ""))
                }
            }
            "pat" ->
                JSONObject().apply {
                    put("ty", "pat")
                    put("auth", fetchPat(client, prefixUrl, token, userAgent))
                }
            "clientmetrics" -> clientMetricsAnswer()
            "captcha" ->
                throw FastlyChallengeError(
                    "Firefox Accounts requires a CAPTCHA. Sign in again to continue."
                )
            else -> throw FastlyChallengeError("unsupported Fastly challenge type '$type'")
        }
    }

    private fun installChallengeCookies(solverJar: SimpleCookieJar) {
        val cookies = solverJar.snapshotAll()
        if (cookies.isEmpty())
            throw FastlyChallengeError("challenge completed but no cookies were issued")

        val targetHosts = allowedHosts()

        for (cookie in cookies) {
            val hostClean = cookie.domain.trimStart('.')
            val origin =
                runCatching {
                    okhttp3.HttpUrl.Builder().scheme("https").host(hostClean).build()
                }.getOrNull()
            if (origin != null) {
                cookieJar.saveFromResponse(origin, listOf(cookie))
            }

            val domainCookie =
                if (hostClean.endsWith("firefox.com")) {
                    runCatching {
                        Cookie.Builder()
                            .name(cookie.name)
                            .value(cookie.value)
                            .domain("firefox.com")
                            .path("/")
                            .apply { if (cookie.secure) secure() }
                            .apply { if (cookie.httpOnly) httpOnly() }
                            .expiresAt(cookie.expiresAt)
                            .build()
                    }.getOrNull()
                } else null

            if (domainCookie != null) {
                val firefoxOrigin =
                    runCatching {
                        okhttp3.HttpUrl.Builder().scheme("https").host("firefox.com").build()
                    }.getOrNull()
                if (firefoxOrigin != null) {
                    cookieJar.saveFromResponse(firefoxOrigin, listOf(domainCookie))
                }
            }

            for (targetHost in targetHosts) {
                val targetOrigin =
                    runCatching {
                        okhttp3.HttpUrl.Builder().scheme("https").host(targetHost).build()
                    }.getOrNull() ?: continue
                val hostCookie =
                    runCatching {
                        Cookie.Builder()
                            .name(cookie.name)
                            .value(cookie.value)
                            .domain(targetHost)
                            .path("/")
                            .apply { if (cookie.secure) secure() }
                            .apply { if (cookie.httpOnly) httpOnly() }
                            .expiresAt(cookie.expiresAt)
                            .build()
                    }.getOrNull()
                if (hostCookie != null) {
                    cookieJar.saveFromResponse(targetOrigin, listOf(hostCookie))
                }
            }
        }

        cookieJar.syncToWebView(cookieJar.snapshotAll())
    }

    private fun newSolverClient(solverJar: SimpleCookieJar): OkHttpClient =
        ControlPlaneHttp.client
            .newBuilder()
            .cookieJar(solverJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(SOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(SOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    private suspend fun solveChallengeOnHost(base: String, userAgent: String): Boolean {
        val solverJar = SimpleCookieJar()
        val solver = newSolverClient(solverJar)
        val pageUrl = "$base/"

        val page =
            try {
                fetchText(solver, pageUrl, userAgent = userAgent, allowChallenge = true)
            } catch (_: Exception) {
                return false
            }
        if (!isChallengePage(page)) {
            return false
        }
        solveChallengePage(page, base, userAgent, solver, solverJar)
        return true
    }

    private suspend fun solveChallengePage(
        page: String,
        base: String,
        userAgent: String,
        solver: OkHttpClient,
        solverJar: SimpleCookieJar,
    ) {
        val pageUrl = "$base/"

        val prefixMatch =
            CHALLENGE_PREFIX_REGEX.find(page)
                ?: throw FastlyChallengeError("challenge asset prefix not found on $base")
        val prefixUrl = base + prefixMatch.value

        val script = fetchText(solver, "$prefixUrl/script.js?reload=true", userAgent = userAgent)
        var (challenges, token) = parseChallengeInit(script, prefixMatch.value)

        repeat(MAX_POST_BACK_ROUNDS) {
            val answers = JSONArray()
            for (i in 0 until challenges.length()) {
                answers.put(
                    answerChallenge(
                        solver,
                        prefixUrl,
                        token,
                        challenges.getJSONObject(i),
                        userAgent,
                    )
                )
            }
            val postBody =
                JSONObject()
                    .apply {
                        put("token", token)
                        put("data", answers)
                    }
                    .toString()
                    .toRequestBody("application/json".toMediaType())
            val builder = Request.Builder().url("$prefixUrl/fst-post-back")
            for ((key, value) in FoxyRemoteConfig.current.solver.headers) {
                builder.header(key, value)
            }
            val request =
                builder
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .header("Origin", baseOrigin(prefixUrl))
                    .post(postBody)
                    .build()
            solver.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful)
                    throw FastlyChallengeError("challenge post-back returned HTTP ${response.code}")
                val body = JSONObject(response.body?.readBoundedText().orEmpty())
                if (body.optString("status") == "success") {
                    val verificationPage =
                        fetchText(solver, pageUrl, userAgent = userAgent, allowChallenge = true)
                    if (isChallengePage(verificationPage))
                        throw FastlyChallengeError("challenge cookie not accepted on this exit IP")
                    installChallengeCookies(solverJar)
                    return
                }
                val nextChallenges = body.optJSONArray("ch")
                val nextToken = body.optString("tok", "")
                if (nextChallenges == null || nextChallenges.length() == 0 || nextToken.isEmpty()) {
                    throw FastlyChallengeError("unexpected challenge post-back response")
                }
                challenges = nextChallenges
                token = nextToken
            }
        }
        throw FastlyChallengeError(
            "Fastly challenge did not complete within $MAX_POST_BACK_ROUNDS rounds"
        )
    }
}
