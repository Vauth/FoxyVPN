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
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class FastlyChallengeError(message: String) : Exception(message)

private const val SOLVE_TIMEOUT_SECONDS = 60L

private const val MAX_POST_BACK_ROUNDS = 8

private const val SOLVER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

private const val POW_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

private const val HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

private val CHALLENGE_PREFIX_REGEX = Regex("/_fs-ch-[A-Za-z0-9]+")
private val INIT_CALL_REGEX = Regex("\\binit\\s*\\(\\s*\\[")

class FastlyChallengeSolver(private val cookieJar: SimpleCookieJar) {

    private companion object {
        val solverMutex = Mutex()
    }

    suspend fun solveAndInstall(
        challengedBody: String? = null,
        challengedOrigin: String? = null,
        userAgent: String = SOLVER_USER_AGENT,
    ) =
        withTimeout(90_000L) {
            solverMutex.withLock {
                withContext(Dispatchers.IO) {
                    val origin =
                        (challengedOrigin ?: "https://api.accounts.firefox.com").toHttpUrl()
                    require(
                        origin.scheme == "https" &&
                            origin.port == 443 &&
                            origin.username.isEmpty() &&
                            origin.password.isEmpty() &&
                            origin.host in
                                setOf(
                                    "vpn.mozilla.org",
                                    "api.accounts.firefox.com",
                                    "oauth.accounts.firefox.com",
                                    "accounts.firefox.com",
                                )
                    ) {
                        "Untrusted challenge origin"
                    }
                    cookieJar.syncFromWebView()
                    val base = "https://${origin.host}"
                    if (!challengedBody.isNullOrEmpty() && isChallengePage(challengedBody)) {
                        solveChallengePage(challengedBody, base, userAgent)
                        return@withContext
                    }
                    val bases =
                        listOf(
                            base,
                            "https://accounts.firefox.com",
                            "https://api.accounts.firefox.com",
                        ).distinct()
                    val candidateUrls =
                        bases.flatMap { baseHost ->
                            if (baseHost.contains("accounts.firefox.com") && !baseHost.contains("api.")) {
                                listOf("$baseHost/", "$baseHost/?context=fx_desktop_v3&service=sync&action=email")
                            } else {
                                listOf("$baseHost/")
                            }
                        }
                    var lastError: FastlyChallengeError? = null
                    for (candidateUrl in candidateUrls) {
                        val candidateBase =
                            if (candidateUrl.contains('?')) candidateUrl.substringBefore('?').trimEnd('/')
                            else candidateUrl.trimEnd('/')
                        for (candidateUA in listOf(userAgent, SOLVER_USER_AGENT, FirefoxAccountHeaders.userAgent).distinct()) {
                            try {
                                val page =
                                    fetchText(
                                        newSolverClient(SimpleCookieJar()),
                                        candidateUrl,
                                        userAgent = candidateUA,
                                        allowChallenge = true,
                                    )
                                if (!isChallengePage(page)) continue
                                solveChallengePage(page, candidateBase, candidateUA)
                                return@withContext
                            } catch (error: FastlyChallengeError) {
                                lastError = error
                            } catch (e: Exception) {
                                lastError = FastlyChallengeError("Error probing $candidateUrl: ${e.message}")
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
        userAgent: String = SOLVER_USER_AGENT,
        allowChallenge: Boolean = false,
    ): String {
        val request =
            Request.Builder()
                .url(url)
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

    private fun parseChallengeInit(script: String): Pair<JSONArray, String> {
        for (match in INIT_CALL_REGEX.findAll(script).toList().asReversed()) {
            val start = match.range.last
            var depth = 0
            var quoted = false
            var escaped = false
            for (index in start until script.length) {
                val char = script[index]
                if (quoted) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> quoted = false
                    }
                    continue
                }
                when (char) {
                    '"' -> quoted = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth != 0) continue
                        val tail = script.substring(index + 1).trimStart()
                        if (!tail.startsWith(",")) break
                        val args = tail.drop(1).trimStart()
                        if (!args.startsWith('"')) break
                        var end = 1
                        var escape = false
                        while (end < args.length) {
                            val next = args[end]
                            if (!escape && next == '"') break
                            escape = !escape && next == '\\'
                            end++
                        }
                        if (
                            end >= args.length ||
                                !args.substring(end + 1).trimStart().startsWith(",")
                        )
                            break
                        try {
                            val challenges = JSONArray(script.substring(start, index + 1))
                            val token =
                                JSONArray("[" + args.substring(0, end + 1) + "]").getString(0)
                            if (
                                challenges.length() > 0 &&
                                    challenges.length() <= 16 &&
                                    token.isNotBlank() &&
                                    (0 until challenges.length()).all {
                                        val item = challenges.optJSONObject(it)
                                        item != null &&
                                            item.opt("ty") is String &&
                                            item.getString("ty").isNotBlank()
                                    }
                            )
                                return challenges to token
                        } catch (_: org.json.JSONException) {
                            break
                        }
                        break
                    }
                }
            }
        }
        throw FastlyChallengeError("Could not read the Fastly challenge")
    }

    private suspend fun fetchPat(
        client: OkHttpClient,
        prefixUrl: String,
        token: String,
        userAgent: String,
    ): String {
        val patUrl = "$prefixUrl/pat?token=${URLEncoder.encode(token, "UTF-8")}"
        val request =
            Request.Builder()
                .url(patUrl)
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

        val targetHosts =
            listOf(
                "api.accounts.firefox.com",
                "accounts.firefox.com",
                "oauth.accounts.firefox.com",
                "vpn.mozilla.org",
            )

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
            .protocols(listOf(Protocol.HTTP_1_1))
            .cookieJar(solverJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(SOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(SOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    private suspend fun solveChallengePage(page: String, base: String, userAgent: String) {
        val solverJar = SimpleCookieJar()
        val solver = newSolverClient(solverJar)
        val pageUrl = "$base/"

        val prefixMatch =
            CHALLENGE_PREFIX_REGEX.find(page)
                ?: throw FastlyChallengeError("challenge asset prefix not found on $base")
        val prefixUrl = base + prefixMatch.value

        val script = fetchText(solver, "$prefixUrl/script.js?reload=true", userAgent = userAgent)
        var (challenges, token) = parseChallengeInit(script)

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
            val request =
                Request.Builder()
                    .url("$prefixUrl/fst-post-back")
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
