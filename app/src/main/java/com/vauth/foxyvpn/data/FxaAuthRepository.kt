package com.vauth.foxyvpn.data

import android.util.Base64
import com.vauth.foxyvpn.data.model.RuntimeAuth
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

private const val TAG = "FxaAuthRepository"

enum class SessionStatus {
    ACTIVE,
    NEEDS_LOGIN,
    UNREACHABLE,
}

class FxaApiError(message: String, val errno: Int?, val statusCode: Int?) : Exception(message)

val FxaApiError.isUnconfirmedSession: Boolean
    get() =
        errno == 104 ||
            (statusCode == 400 &&
                message?.contains("Unconfirmed session", ignoreCase = true) == true)

fun Throwable.isUnconfirmedSession(): Boolean =
    (this as? FxaApiError)?.isUnconfirmedSession == true ||
        message?.contains("Unconfirmed session", ignoreCase = true) == true

class FxaRefreshFailure(message: String, val permanent: Boolean, cause: Throwable? = null) :
    Exception(message, cause)

class FastlyBlockedException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val FXA_AUTH_SERVER = "https://api.accounts.firefox.com/v1"
private const val FXA_OAUTH_SERVER = "https://oauth.accounts.firefox.com/v1"
private const val FIREFOX_CLIENT_ID = "5882386c6d801776"
private const val OAUTH_SCOPE = "profile https://identity.mozilla.com/apps/vpn"
private const val PROTOCOL_VERSION = "identity.mozilla.com/picl/v1/"
private const val FXA_MAX_CHALLENGE_ATTEMPTS = 5
private const val DEFAULT_ACCESS_TOKEN_TTL_SECONDS = 24 * 60 * 60

private const val WEBCHANNEL_LOGIN_URL =
    "https://accounts.firefox.com/?context=fx_desktop_v3&service=sync&action=email"

private val jsonMediaType = "application/json".toMediaType()

class FxaAuthRepository(
    private val tokenStore: TokenStore,
    private val challengeSolver: FastlyChallengeSolver =
        FastlyChallengeSolver(ControlPlaneHttp.cookieJar),
) {
    private val refreshMutex = Mutex()
    private val loginMutex = Mutex()
    private val client =
        ControlPlaneHttp.client
            .newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    val storageError = tokenStore.storageError

    fun getBrowserLoginUrl(): String = WEBCHANNEL_LOGIN_URL

    fun buildOAuthHawkHeader(sessionToken: String): Pair<String, String> {
        val clean = sessionToken.trim().replace(Regex("[\\s\"]"), "").lowercase()
        require(Regex("^[0-9a-f]{64}$").matches(clean)) {
            "The sign-in page did not hand over a usable session token"
        }
        val url = "$FXA_AUTH_SERVER/oauth/token".toHttpUrl()
        val body =
            JSONObject().apply {
                put("client_id", FIREFOX_CLIENT_ID)
                put("grant_type", "fxa-credentials")
                put("scope", OAUTH_SCOPE)
                put("access_type", "offline")
            }
        val bodyStr = body.toString()
        val (tokenId, hmacKey) = deriveHawkCredentials(clean)
        val header = hawkHeader("POST", url, tokenId, hmacKey, bodyStr.toByteArray(Charsets.UTF_8))
        return header to bodyStr
    }

    suspend fun completeBrowserLoginWithTokens(
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            val epoch = tokenStore.sessionEpoch
            ControlPlaneHttp.cookieJar.syncFromWebView()
            runCatching {
                    loginMutex.withLock {
                        check(tokenStore.sessionEpoch == epoch) { "Sign-in was superseded" }
                        val ttl =
                            if (expiresInSeconds > 0) expiresInSeconds
                            else DEFAULT_ACCESS_TOKEN_TTL_SECONDS.toLong()
                        tokenStore.completeLoginIfCurrent(
                            RuntimeAuth(
                                accessToken = accessToken.trim(),
                                refreshToken = refreshToken?.trim()?.ifBlank { null },
                                expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + ttl,
                            ),
                            epoch,
                        )
                        Unit
                    }
                }
                .onFailure { if (it is CancellationException) throw it }
        }

    suspend fun completeBrowserLogin(sessionToken: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val epoch = tokenStore.sessionEpoch
            ControlPlaneHttp.cookieJar.syncFromWebView()
            runCatching {
                    loginMutex.withLock {
                        val clean = sessionToken.trim().replace(Regex("[\\s\"]"), "").lowercase()
                        require(Regex("^[0-9a-f]{64}$").matches(clean)) {
                            "The sign-in page did not hand over a usable session token"
                        }
                        check(tokenStore.sessionEpoch == epoch) { "Sign-in was superseded" }
                        FirefoxAccountHeaders.refresh(client)
                        completeLogin(clean, epoch)
                    }
                }
                .onFailure { if (it is CancellationException) throw it }
        }

    suspend fun restoreSession(): SessionStatus =
        withContext(Dispatchers.IO) { restoreSessionInternal() }

    private suspend fun restoreSessionInternal(): SessionStatus {
        val epoch = tokenStore.sessionEpoch
        val stored =
            runCatching { tokenStore.loadAuth() }.getOrNull() ?: return SessionStatus.NEEDS_LOGIN
        if (stored.refreshToken == null && !tokenStore.hasValidAccessToken()) {
            return if (tokenStore.clearIfCurrent(epoch)) SessionStatus.NEEDS_LOGIN
            else if (tokenStore.hasValidAccessToken()) SessionStatus.ACTIVE
            else SessionStatus.UNREACHABLE
        }
        if (tokenStore.hasValidAccessToken()) return SessionStatus.ACTIVE

        return try {
            ensureFreshAccessToken()
            SessionStatus.ACTIVE
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: FxaRefreshFailure) {
            if (failure.permanent) {
                AppLogger.w(TAG, "FxA rejected the stored session; signing out", failure)
                if (tokenStore.clearIfCurrent(epoch)) SessionStatus.NEEDS_LOGIN
                else if (tokenStore.hasValidAccessToken()) SessionStatus.ACTIVE
                else SessionStatus.UNREACHABLE
            } else {
                AppLogger.w(
                    TAG,
                    "could not renew the session right now; staying signed in",
                    failure,
                )
                SessionStatus.UNREACHABLE
            }
        } catch (unexpected: Exception) {
            AppLogger.w(TAG, "unexpected error while restoring the session", unexpected)
            SessionStatus.UNREACHABLE
        }
    }

    suspend fun ensureFreshAccessToken(force: Boolean = false): RuntimeAuth =
        withContext(Dispatchers.IO) { ensureFreshAccessTokenInternal(force) }

    private suspend fun ensureFreshAccessTokenInternal(force: Boolean): RuntimeAuth =
        refreshMutex.withLock {
            val epoch = tokenStore.sessionEpoch
            val current =
                tokenStore.loadAuth()
                    ?: run {
                        tokenStore.storageError.value?.let {
                            throw FxaRefreshFailure(it, permanent = false)
                        }
                        throw FxaRefreshFailure("Not signed in", permanent = true)
                    }
            if (!force && tokenStore.hasValidAccessToken()) return@withLock current

            val refreshToken =
                current.refreshToken
                    ?: throw FxaRefreshFailure(
                        "The stored session has no refresh token; sign in again.",
                        permanent = true,
                    )

            val body =
                JSONObject().apply {
                    put("client_id", FIREFOX_CLIENT_ID)
                    put("grant_type", "refresh_token")
                    put("refresh_token", refreshToken)
                    put("scope", OAUTH_SCOPE)
                }

            val tokenData =
                try {
                    FirefoxAccountHeaders.refresh(client)
                    executeFxaRequest(FXA_OAUTH_SERVER, "/oauth/token", jsonBody = body)
                } catch (rejected: FxaApiError) {
                    throw FxaRefreshFailure(
                        rejected.message ?: "FxA rejected the refresh token",
                        permanent = rejected.isPermanentRefreshRejection(),
                        cause = rejected,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (transport: Exception) {
                    throw FxaRefreshFailure(
                        transport.message ?: "Could not reach the Firefox Accounts server",
                        permanent = false,
                        cause = transport,
                    )
                }

            val accessToken =
                tokenData.optString("access_token").takeIf { it.isNotBlank() }
                    ?: throw FxaRefreshFailure(
                        "FxA returned no access token for the refresh grant",
                        permanent = false,
                    )

            val renewed =
                RuntimeAuth(
                    accessToken = accessToken,
                    refreshToken =
                        tokenData.optString("refresh_token", "").ifBlank { null } ?: refreshToken,
                    expiresAtEpochSeconds = expiryFrom(tokenData),
                )
            tokenStore.saveAuthIfCurrent(renewed, epoch)
            renewed
        }

    suspend fun currentAccessToken(): String? =
        withContext(Dispatchers.IO) {
            try {
                ensureFreshAccessToken().accessToken
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: FxaRefreshFailure) {
                if (!failure.permanent && tokenStore.hasValidAccessToken())
                    tokenStore.loadAuth()?.accessToken
                else null
            }
        }

    suspend fun refreshAccessToken(): RuntimeAuth? =
        try {
            ensureFreshAccessToken(force = true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: FxaRefreshFailure) {
            AppLogger.w(TAG, "access token renewal failed", failure)
            null
        }

    private fun FxaApiError.isPermanentRefreshRejection(): Boolean {
        return statusCode == 400 || statusCode == 401 || statusCode == 403
    }

    private fun expiryFrom(tokenData: JSONObject): Long {
        val expiresIn =
            tokenData.optInt("expires_in", 0).takeIf { it > 0 } ?: DEFAULT_ACCESS_TOKEN_TTL_SECONDS
        return System.currentTimeMillis() / 1000 + expiresIn
    }

    private suspend fun completeLogin(sessionToken: String, epoch: Long) {
        val tokenData = oauthToken(sessionToken)
        coroutineContext.ensureActive()
        tokenStore.completeLoginIfCurrent(
            RuntimeAuth(
                accessToken =
                    tokenData.optString("access_token").takeIf { it.isNotBlank() }
                        ?: error("Firefox Accounts returned no access token"),
                refreshToken = tokenData.optString("refresh_token", "").ifBlank { null },
                expiresAtEpochSeconds = expiryFrom(tokenData),
            ),
            epoch,
        )
    }

    private suspend fun oauthToken(sessionToken: String): JSONObject {
        val body =
            JSONObject().apply {
                put("client_id", FIREFOX_CLIENT_ID)
                put("grant_type", "fxa-credentials")
                put("scope", OAUTH_SCOPE)
                put("access_type", "offline")
            }
        return executeFxaRequest(
            baseUrl = FXA_AUTH_SERVER,
            path = "/oauth/token",
            jsonBody = body,
            sessionToken = sessionToken,
        )
    }

    private suspend fun executeFxaRequest(
        baseUrl: String,
        path: String,
        method: String = "POST",
        jsonBody: JSONObject,
        sessionToken: String? = null,
    ): JSONObject =
        withTimeout(120_000L) {
            withContext(Dispatchers.IO) {
                val url = "$baseUrl$path".toHttpUrl()
                val origin = "${url.scheme}://${url.host}"
                val bodyBytes = jsonBody.toString().toByteArray(Charsets.UTF_8)
                val hawkCreds = sessionToken?.let { deriveHawkCredentials(it) }

                fun buildRequest(): Request {
                    val builder =
                        Request.Builder()
                            .url(url)
                            .applyFirefoxHeaders()
                            .header("Content-Type", "application/json")
                    if (hawkCreds != null) {
                        builder.header(
                            "Authorization",
                            hawkHeader(method, url, hawkCreds.first, hawkCreds.second, bodyBytes),
                        )
                    }
                    builder.method(method, bodyBytes.toRequestBody(jsonMediaType))
                    return builder.build()
                }

                var response: Response = client.newCall(buildRequest()).awaitResponse()
                var attempts = 1
                while (response.code == 406 && attempts < FXA_MAX_CHALLENGE_ATTEMPTS) {
                    val challengedBody = response.use { it.body?.readBoundedText().orEmpty() }
                    try {
                        ControlPlaneHttp.cookieJar.syncFromWebView()
                        challengeSolver.solveAndInstall(
                            challengedBody,
                            origin,
                            FirefoxAccountHeaders.userAgent,
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (solveFailure: Exception) {
                        AppLogger.w(TAG, "could not solve the Fastly challenge", solveFailure)
                        throw FastlyBlockedException(FRIENDLY_406_MESSAGE, solveFailure)
                    }
                    response = client.newCall(buildRequest()).awaitResponse()
                    attempts++
                }

                response.use { resp ->
                    val text = resp.body?.readBoundedText().orEmpty()
                    if (resp.code >= 400) {
                        if (resp.code == 406) {
                            throw FastlyBlockedException(FRIENDLY_406_MESSAGE)
                        }
                        val errorBody =
                            try {
                                JSONObject(text)
                            } catch (e: Exception) {
                                JSONObject()
                            }
                        val errno = if (errorBody.has("errno")) errorBody.optInt("errno") else null
                        val message =
                            when {
                                errorBody.length() > 0 ->
                                    errorBody.optString("message", "HTTP ${resp.code}")
                                text.isEmpty() -> "HTTP ${resp.code}"
                                else -> text.take(200)
                            }
                        throw FxaApiError(message, errno, resp.code)
                    }
                    if (text.isEmpty()) JSONObject() else JSONObject(text)
                }
            }
        }

    private fun deriveHawkCredentials(sessionTokenHex: String): Pair<String, ByteArray> {
        val sessionToken = sessionTokenHex.hexToBytes()
        val expanded = hkdf(sessionToken, "${PROTOCOL_VERSION}sessionToken", 64)
        return expanded.copyOfRange(0, 32).toHex() to expanded.copyOfRange(32, 64)
    }

    private fun hawkHeader(
        method: String,
        url: HttpUrl,
        tokenId: String,
        hmacKey: ByteArray,
        body: ByteArray,
    ): String {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonceBytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
        val nonce =
            Base64.encodeToString(
                nonceBytes,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
        val path = url.encodedPath + if (url.encodedQuery != null) "?${url.encodedQuery}" else ""

        var payloadHash = ""
        if (body.isNotEmpty()) {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("hawk.1.payload\napplication/json\n".toByteArray(Charsets.UTF_8))
            digest.update(body)
            digest.update("\n".toByteArray(Charsets.UTF_8))
            payloadHash = Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
        }

        val normalized =
            "hawk.1.header\n$ts\n$nonce\n${method.uppercase()}\n$path\n${url.host}\n${url.port}\n$payloadHash\n\n"

        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(hmacKey, "HmacSHA256")) }
        val macB64 =
            Base64.encodeToString(
                mac.doFinal(normalized.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP,
            )

        var header = "Hawk id=\"$tokenId\", ts=\"$ts\", nonce=\"$nonce\", mac=\"$macB64\""
        if (payloadHash.isNotEmpty()) header += ", hash=\"$payloadHash\""
        return header
    }
}

private const val FRIENDLY_406_MESSAGE =
    "Mozilla's bot protection rejected this request (HTTP 406) and the automatic " +
        "challenge could not be completed on this network. Please switch networks " +
        "(Wi-Fi <-> mobile data) and try again."

private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun hkdf(
    ikm: ByteArray,
    info: String,
    length: Int,
    salt: ByteArray = ByteArray(32),
): ByteArray {
    val prk = hmacSha256(salt, ikm)
    val infoBytes = info.toByteArray(Charsets.UTF_8)
    val result = ByteArray(length)
    var previousBlock = ByteArray(0)
    var generated = 0
    var counter = 1
    while (generated < length) {
        val block = hmacSha256(prk, previousBlock + infoBytes + byteArrayOf(counter.toByte()))
        val toCopy = minOf(block.size, length - generated)
        System.arraycopy(block, 0, result, generated, toCopy)
        generated += toCopy
        previousBlock = block
        counter++
    }
    return result
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
