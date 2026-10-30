package com.vauth.foxyvpn.data

import android.util.Base64
import com.vauth.foxyvpn.data.model.RuntimeAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TAG = "FxaAuthRepository"

enum class SessionStatus {
    ACTIVE,
    NEEDS_LOGIN,
    UNREACHABLE,
}

class FxaApiError(message: String, val errno: Int?, val statusCode: Int?) : Exception(message)

val FxaApiError.isUnconfirmedSession: Boolean
    get() = errno == 104 || (statusCode == 400 && message?.contains("Unconfirmed session", ignoreCase = true) == true)

fun Throwable.isUnconfirmedSession(): Boolean =
    (this as? FxaApiError)?.isUnconfirmedSession == true ||
        message?.contains("Unconfirmed session", ignoreCase = true) == true


class FxaRefreshFailure(
    message: String,
    val permanent: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)

class FastlyBlockedException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val FXA_AUTH_SERVER = "https://api.accounts.firefox.com/v1"
private const val FXA_OAUTH_SERVER = "https://oauth.accounts.firefox.com/v1"
private const val FIREFOX_CLIENT_ID = "5882386c6d801776"
private const val OAUTH_SCOPE = "profile https://identity.mozilla.com/apps/vpn"
private const val PROTOCOL_VERSION = "identity.mozilla.com/picl/v1/"
private const val FXA_MAX_CHALLENGE_ATTEMPTS = 3
private const val DEFAULT_ACCESS_TOKEN_TTL_SECONDS = 24 * 60 * 60

private const val WEBCHANNEL_LOGIN_URL =
    "https://accounts.firefox.com/?context=fx_desktop_v3&service=sync&action=email"

private val jsonMediaType = "application/json".toMediaType()

class FxaAuthRepository(
    private val tokenStore: TokenStore,
    private val challengeSolver: FastlyChallengeSolver = FastlyChallengeSolver(ControlPlaneHttp.cookieJar),
) {
    fun getBrowserLoginUrl(): String = WEBCHANNEL_LOGIN_URL

    suspend fun completeBrowserLogin(sessionToken: String): Result<Unit> = runCatching {
        val clean = sessionToken.trim().replace(Regex("[\\s\"]"), "").lowercase()
        if (!Regex("^[0-9a-f]{64}$").matches(clean)) {
            error("The sign-in page did not hand over a usable session token")
        }
        completeLogin(clean)
    }

    suspend fun restoreSession(): SessionStatus {
        val stored = runCatching { tokenStore.loadAuth() }.getOrNull()
            ?: return SessionStatus.NEEDS_LOGIN
        if (stored.refreshToken == null && !tokenStore.hasValidAccessToken()) {
            runCatching { tokenStore.clear() }
            return SessionStatus.NEEDS_LOGIN
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
                runCatching { tokenStore.clear() }
                SessionStatus.NEEDS_LOGIN
            } else {
                AppLogger.w(TAG, "could not renew the session right now; staying signed in", failure)
                SessionStatus.UNREACHABLE
            }
        } catch (unexpected: Exception) {
            AppLogger.w(TAG, "unexpected error while restoring the session", unexpected)
            SessionStatus.UNREACHABLE
        }
    }

    suspend fun ensureFreshAccessToken(force: Boolean = false): RuntimeAuth {
        val current = tokenStore.loadAuth()
            ?: throw FxaRefreshFailure("Not signed in", permanent = true)
        if (!force && tokenStore.hasValidAccessToken()) return current

        val refreshToken = current.refreshToken
            ?: throw FxaRefreshFailure(
                "The stored session has no refresh token; sign in again.",
                permanent = true,
            )

        val body = JSONObject().apply {
            put("client_id", FIREFOX_CLIENT_ID)
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
            put("scope", OAUTH_SCOPE)
        }

        val tokenData = try {
            executeFxaRequest(
                baseUrl = FXA_OAUTH_SERVER,
                path = "/oauth/token",
                jsonBody = body,
            )
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

        val accessToken = tokenData.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw FxaRefreshFailure(
                "FxA returned no access token for the refresh grant",
                permanent = false,
            )

        val renewed = RuntimeAuth(
            accessToken = accessToken,
            refreshToken = tokenData.optString("refresh_token", "").ifBlank { null } ?: refreshToken,
            expiresAtEpochSeconds = expiryFrom(tokenData),
        )
        runCatching { tokenStore.saveAuth(renewed) }
            .onFailure { AppLogger.w(TAG, "could not persist the renewed token", it) }
        return renewed
    }

    suspend fun currentAccessToken(): String? = try {
        ensureFreshAccessToken().accessToken
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: FxaRefreshFailure) {
        if (failure.permanent) null else tokenStore.loadAuth()?.accessToken
    }

    suspend fun refreshAccessToken(): RuntimeAuth? = try {
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
        val expiresIn = tokenData.optInt("expires_in", 0)
            .takeIf { it > 0 } ?: DEFAULT_ACCESS_TOKEN_TTL_SECONDS
        return System.currentTimeMillis() / 1000 + expiresIn
    }

    private suspend fun completeLogin(sessionToken: String) {
        val tokenData = oauthToken(sessionToken)
        tokenStore.saveAuth(
            RuntimeAuth(
                accessToken = tokenData.getString("access_token"),
                refreshToken = tokenData.optString("refresh_token", "").ifBlank { null },
                expiresAtEpochSeconds = expiryFrom(tokenData),
            ),
        )
    }

    private suspend fun oauthToken(sessionToken: String): JSONObject {
        val body = JSONObject().apply {
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
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = "$baseUrl$path".toHttpUrl()
        val origin = "${url.scheme}://${url.host}"
        val bodyBytes = jsonBody.toString().toByteArray(Charsets.UTF_8)
        var tokenId: String? = null
        var hmacKey: ByteArray? = null
        if (sessionToken != null) {
            val (id, key) = deriveHawkCredentials(sessionToken)
            tokenId = id
            hmacKey = key
        }

        fun buildRequest(): Request {
            val builder = Request.Builder().url(url).applyMozillaVpnHeaders()
                .header("Content-Type", "application/json")
            if (sessionToken != null) {
                builder.header("Authorization", hawkHeader(method, url, tokenId!!, hmacKey!!, bodyBytes))
            }
            builder.method(method, bodyBytes.toRequestBody(jsonMediaType))
            return builder.build()
        }

        var response: Response = ControlPlaneHttp.client.newCall(buildRequest()).execute()
        var attempts = 1
        while (response.code == 406 && attempts < FXA_MAX_CHALLENGE_ATTEMPTS) {
            val challengedBody = response.use { it.body?.string().orEmpty() }
            try {
                challengeSolver.solveAndInstall(challengedBody, origin)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (solveFailure: Exception) {
                AppLogger.w(TAG, "could not solve the Fastly challenge", solveFailure)
                throw FastlyBlockedException(FRIENDLY_406_MESSAGE, solveFailure)
            }
            response = ControlPlaneHttp.client.newCall(buildRequest()).execute()
            attempts++
        }

        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code >= 400) {
                if (resp.code == 406) {
                    throw FastlyBlockedException(FRIENDLY_406_MESSAGE)
                }
                val errorBody = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    JSONObject()
                }
                val errno = if (errorBody.has("errno")) errorBody.optInt("errno") else null
                val message = when {
                    errorBody.length() > 0 -> errorBody.optString("message", "HTTP ${resp.code}")
                    text.isEmpty() -> "HTTP ${resp.code}"
                    else -> text.take(200)
                }
                throw FxaApiError(message, errno, resp.code)
            }
            if (text.isEmpty()) JSONObject() else JSONObject(text)
        }
    }

    private fun deriveHawkCredentials(sessionTokenHex: String): Pair<String, ByteArray> {
        val sessionToken = sessionTokenHex.hexToBytes()
        val expanded = hkdf(sessionToken, "${PROTOCOL_VERSION}sessionToken", 64)
        return expanded.copyOfRange(0, 32).toHex() to expanded.copyOfRange(32, 64)
    }

    private fun hawkHeader(method: String, url: HttpUrl, tokenId: String, hmacKey: ByteArray, body: ByteArray): String {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonceBytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
        val nonce = Base64.encodeToString(nonceBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val path = url.encodedPath + if (url.encodedQuery != null) "?${url.encodedQuery}" else ""

        var payloadHash = ""
        if (body.isNotEmpty()) {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("hawk.1.payload\napplication/json\n".toByteArray(Charsets.UTF_8))
            digest.update(body)
            digest.update("\n".toByteArray(Charsets.UTF_8))
            payloadHash = Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
        }

        val normalized = "hawk.1.header\n$ts\n$nonce\n${method.uppercase()}\n$path\n${url.host}\n${url.port}\n$payloadHash\n\n"

        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(hmacKey, "HmacSHA256")) }
        val macB64 = Base64.encodeToString(mac.doFinal(normalized.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

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

private fun hkdf(ikm: ByteArray, info: String, length: Int, salt: ByteArray = ByteArray(32)): ByteArray {
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
