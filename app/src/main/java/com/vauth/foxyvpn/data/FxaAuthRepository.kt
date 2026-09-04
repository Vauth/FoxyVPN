package com.vauth.foxyvpn.data

import android.util.Base64
import com.vauth.foxyvpn.data.model.RuntimeAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class LoginStep { CREDENTIALS, TWO_FACTOR }

class FxaApiError(message: String, val errno: Int?, val statusCode: Int?) : Exception(message)

private const val FXA_AUTH_SERVER = "https://api.accounts.firefox.com/v1"
private const val FIREFOX_CLIENT_ID = "5882386c6d801776"
private const val OAUTH_SCOPE = "profile https://identity.mozilla.com/apps/vpn"
private const val PROTOCOL_VERSION = "identity.mozilla.com/picl/v1/"
private const val PBKDF2_ROUNDS = 1000
private const val STRETCHED_PW_LEN = 32
private const val HKDF_LEN = 32
private const val VERIFICATION_METHOD_EMAIL_2FA = "email-2fa"
private const val FXA_ERRNO_INVALID_PARAMETER = 107
private const val FXA_MAX_CHALLENGE_ATTEMPTS = 5

private val jsonMediaType = "application/json".toMediaType()

class FxaAuthRepository(
    private val tokenStore: TokenStore,
    private val challengeSolver: FastlyChallengeSolver = FastlyChallengeSolver(ControlPlaneHttp.cookieJar),
) {
    private var pendingSessionToken: String? = null

    suspend fun startLogin(email: String, password: String): Result<Boolean> = runCatching {
        val data = loginAttempt(email, password)
        val sessionToken = data.getString("sessionToken")
        pendingSessionToken = sessionToken
        val verified = data.optBoolean("verified", false)
        if (!verified) {
            true
        } else {
            completeLogin(sessionToken)
            false
        }
    }

    suspend fun submitTwoFactorCode(code: String): Result<Boolean> = runCatching {
        val sessionToken = pendingSessionToken ?: error("No pending FxA session. Start sign-in again.")
        fxaDo("POST", "/session/verify_code", sessionToken = sessionToken, jsonBody = JSONObject().put("code", code))
        completeLogin(sessionToken)
        false
    }

    suspend fun refreshAccessToken(): RuntimeAuth? {
        val current = tokenStore.loadAuth() ?: return null
        val refreshToken = current.refreshToken?.takeIf { it.isNotBlank() } ?: return null

        val body = JSONObject().apply {
            put("client_id", FIREFOX_CLIENT_ID)
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
            put("scope", OAUTH_SCOPE)
        }
        val tokenData = fxaDo("POST", "/oauth/token", jsonBody = body)

        val accessToken = tokenData.optString("access_token")
        if (accessToken.isBlank()) return null

        val rotated = tokenData.optString("refresh_token", "").ifBlank { null }
        val renewed = RuntimeAuth(
            accessToken = accessToken,
            refreshToken = rotated ?: refreshToken,
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + tokenData.optInt("expires_in", 0),
        )
        tokenStore.saveAuth(renewed)
        return renewed
    }

    private suspend fun completeLogin(sessionToken: String) {
        val tokenData = oauthToken(sessionToken)
        tokenStore.saveAuth(
            RuntimeAuth(
                accessToken = tokenData.getString("access_token"),
                refreshToken = tokenData.optString("refresh_token", "").ifEmpty { null },
                expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + tokenData.optInt("expires_in", 0),
            ),
        )
        pendingSessionToken = null
    }

    private suspend fun loginAttempt(email: String, password: String, withVerificationMethod: Boolean = true): JSONObject {
        val body = JSONObject().apply {
            put("email", email)
            put("authPW", deriveAuthPw(email, password))
            if (withVerificationMethod) put("verificationMethod", VERIFICATION_METHOD_EMAIL_2FA)
        }
        return try {
            fxaDo("POST", "/account/login", jsonBody = body)
        } catch (e: FxaApiError) {
            if (e.errno == FXA_ERRNO_INVALID_PARAMETER && withVerificationMethod) {
                loginAttempt(email, password, withVerificationMethod = false)
            } else {
                throw e
            }
        }
    }

    private suspend fun oauthToken(sessionToken: String): JSONObject {
        val body = JSONObject().apply {
            put("client_id", FIREFOX_CLIENT_ID)
            put("grant_type", "fxa-credentials")
            put("scope", OAUTH_SCOPE)
            put("access_type", "offline")
        }
        return fxaDo("POST", "/oauth/token", sessionToken = sessionToken, jsonBody = body)
    }

    private suspend fun fxaDo(
        method: String,
        path: String,
        sessionToken: String? = null,
        jsonBody: JSONObject,
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = "$FXA_AUTH_SERVER$path".toHttpUrl()
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
            builder.header("Content-Type", "application/json")
            if (sessionToken != null) {
                builder.header("Authorization", hawkHeader(method, url, tokenId!!, hmacKey!!, bodyBytes))
            }
            builder.method(method, bodyBytes.toRequestBody(jsonMediaType))
            return builder.build()
        }

        var response: Response = ControlPlaneHttp.client.newCall(buildRequest()).execute()
        var attempts = 1
        while (response.code == 406 && attempts < FXA_MAX_CHALLENGE_ATTEMPTS) {
            response.close()
            challengeSolver.solveAndInstall()
            response = ControlPlaneHttp.client.newCall(buildRequest()).execute()
            attempts++
        }

        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code >= 400) {
                val errorBody = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    JSONObject()
                }
                val errno = if (errorBody.has("errno")) errorBody.optInt("errno") else null
                val message = errorBody.optString("message", text.ifEmpty { "HTTP ${resp.code}" })
                throw FxaApiError(message, errno, resp.code)
            }
            if (text.isEmpty()) return@withContext JSONObject()
            return@withContext JSONObject(text)
        }
    }

    private fun deriveQuickStretch(email: String, password: String): ByteArray {
        val salt = "${PROTOCOL_VERSION}quickStretch:$email".toByteArray(Charsets.UTF_8)
        return pbkdf2HmacSha256(password.toByteArray(Charsets.UTF_8), salt, PBKDF2_ROUNDS, STRETCHED_PW_LEN)
    }

    private fun deriveAuthPw(email: String, password: String): String {
        val quickStretched = deriveQuickStretch(email, password)
        return hkdf(quickStretched, "${PROTOCOL_VERSION}authPW", HKDF_LEN).toHex()
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

        val normalized = listOf(
            "hawk.1.header", ts, nonce, method.uppercase(), path, url.host, url.port.toString(), payloadHash, "", "",
        ).joinToString("\n")

        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(hmacKey, "HmacSHA256")) }
        val macB64 = Base64.encodeToString(mac.doFinal(normalized.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

        var header = "Hawk id=\"$tokenId\", ts=\"$ts\", nonce=\"$nonce\", mac=\"$macB64\""
        if (payloadHash.isNotEmpty()) header += ", hash=\"$payloadHash\""
        return header
    }
}

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

private fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray {
    val hLen = 32
    val numBlocks = (keyLengthBytes + hLen - 1) / hLen
    val result = ByteArray(numBlocks * hLen)
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
    for (blockIndex in 1..numBlocks) {
        val blockIndexBytes = ByteBuffer.allocate(4).putInt(blockIndex).array()
        var u = mac.doFinal(salt + blockIndexBytes)
        val t = u.copyOf()
        for (iter in 2..iterations) {
            u = mac.doFinal(u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        System.arraycopy(t, 0, result, (blockIndex - 1) * hLen, hLen)
    }
    return result.copyOf(keyLengthBytes)
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
