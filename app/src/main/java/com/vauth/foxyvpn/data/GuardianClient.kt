package com.vauth.foxyvpn.data

import android.util.Base64
import com.vauth.foxyvpn.data.model.Entitlement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

const val GUARDIAN_ENDPOINT_DEFAULT = "https://vpn.mozilla.org"
private const val TAG = "GuardianClient"
private const val GUARDIAN_MAX_CHALLENGE_ATTEMPTS = 5
private const val GUARDIAN_USER_AGENT = "MozillaVPN/2.35.0 (sys:android 14; iap:true)"

class GuardianHttpError(
    message: String,
    val statusCode: Int? = null,
    val retryAfterMs: Long? = null,
) : Exception(message)

class QuotaExceededError(
    message: String,
    val quotaMax: Long? = null,
    val quotaRemaining: Long? = 0L,
    val quotaReset: Long? = null,
) : Exception(message)

class TokenInvalidError(message: String) : Exception(message)

data class ProxyPass(
    val token: String,
    val expiresAtEpochSeconds: Long?,
    val quotaMax: Long? = null,
    val quotaRemaining: Long? = null,
    val quotaReset: Long? = null,
) {
    override fun toString() = "ProxyPass(expiresAtEpochSeconds=$expiresAtEpochSeconds)"
}

class GuardianClient(
    private val challengeSolver: FastlyChallengeSolver =
        FastlyChallengeSolver(ControlPlaneHttp.cookieJar)
) {

    suspend fun fetchProxyPass(endpoint: String, accessToken: String): ProxyPass =
        withTimeout(120_000L) {
            withContext(Dispatchers.IO) {
                val url = "${endpoint.trimEnd('/')}/api/v1/fpn/token"
                authorizedRequest("GET", url, accessToken).use { resp ->
                    when (resp.code) {
                        401,
                        403 -> throw TokenInvalidError("access token was rejected by Guardian")
                        429 -> {
                            val remaining = resp.header("X-Quota-Remaining")?.trim()?.toLongOrNull()
                                ?: resp.header("Quota-Remaining")?.trim()?.toLongOrNull()
                            if (remaining != null && remaining <= 0L) {
                                throw QuotaExceededError(
                                    "account proxy quota exceeded",
                                    quotaMax = resp.header("X-Quota-Limit")?.trim()?.toLongOrNull()
                                        ?: resp.header("Quota-Limit")?.trim()?.toLongOrNull(),
                                    quotaRemaining = 0L,
                                    quotaReset = resp.header("X-Quota-Reset")?.trim()?.toLongOrNull()
                                        ?: resp.header("Quota-Reset")?.trim()?.toLongOrNull(),
                                )
                            } else {
                                throw GuardianHttpError(
                                    "Guardian rate limited the request; retry later",
                                    429,
                                    retryAfterMillis(resp.header("Retry-After")),
                                )
                            }
                        }
                    }
                    val text = resp.body?.readBoundedText().orEmpty()
                    if (resp.code != 200) {
                        throw GuardianHttpError(
                            "failed to fetch proxy pass: HTTP ${resp.code}",
                            resp.code,
                            retryAfterMillis(resp.header("Retry-After")),
                        )
                    }
                    val body = JSONObject(text)
                    val token = body.optString("token", "")
                    if (token.isBlank())
                        throw GuardianHttpError("proxy pass response did not contain a valid token")
                    val expiresAt =
                        body.optLong("expires_at", 0L).takeIf { it > 0 }
                            ?: jwtExpiryEpochSeconds(token)

                    ProxyPass(
                        token = token,
                        expiresAtEpochSeconds = expiresAt,
                        quotaMax = resp.header("X-Quota-Limit")?.trim()?.toLongOrNull()
                            ?: resp.header("Quota-Limit")?.trim()?.toLongOrNull(),
                        quotaRemaining = resp.header("X-Quota-Remaining")?.trim()?.toLongOrNull()
                            ?: resp.header("Quota-Remaining")?.trim()?.toLongOrNull(),
                        quotaReset = resp.header("X-Quota-Reset")?.trim()?.toLongOrNull()
                            ?: resp.header("Quota-Reset")?.trim()?.toLongOrNull(),
                    )
                }
            }
        }

    suspend fun fetchUserInfo(endpoint: String, accessToken: String): Entitlement =
        withTimeout(120_000L) {
            withContext(Dispatchers.IO) {
                var pass: ProxyPass? = null
                var quotaExceeded: QuotaExceededError? = null
                var passFailure: Exception? = null
                try {
                    pass = fetchProxyPass(endpoint, accessToken)
                } catch (invalid: TokenInvalidError) {
                    runCatching { activateGuardian(endpoint, accessToken) }
                    pass = runCatching { fetchProxyPass(endpoint, accessToken) }.getOrNull()
                } catch (exceeded: QuotaExceededError) {
                    quotaExceeded = exceeded
                } catch (e: Exception) {
                    passFailure = e
                    AppLogger.w(TAG, "could not fetch proxy pass for quota", e)
                }

                val statusUrl = "${endpoint.trimEnd('/')}/api/v1/fpn/status"
                val statusEntitlement =
                    runCatching {
                        authorizedRequest("GET", statusUrl, accessToken).use { resp ->
                            if (resp.code == 200) {
                                val text = resp.body?.readBoundedText().orEmpty()
                                parseEntitlement(JSONObject(text))
                            } else null
                        }
                    }.getOrNull()

                val remaining =
                    pass?.quotaRemaining
                        ?: quotaExceeded?.quotaRemaining
                        ?: statusEntitlement?.quotaRemaining
                val max =
                    pass?.quotaMax
                        ?: quotaExceeded?.quotaMax
                        ?: statusEntitlement?.maxBytes

                if (pass == null && quotaExceeded == null && statusEntitlement == null) {
                    if (passFailure != null) throw passFailure
                    throw GuardianHttpError("could not obtain entitlement from Guardian")
                }

                val limited =
                    if (remaining != null || max != null) true
                    else (statusEntitlement?.limitedBandwidth ?: true)

                Entitlement(
                    maxBytes = max,
                    limitedBandwidth = limited,
                    quotaRemaining = remaining,
                )
            }
        }

    suspend fun activateGuardian(endpoint: String, accessToken: String): Entitlement =
        withTimeout(120_000L) {
            withContext(Dispatchers.IO) {
                val url = "${endpoint.trimEnd('/')}/api/v1/fpn/activate"
                authorizedRequest("POST", url, accessToken).use { resp ->
                    val text = resp.body?.readBoundedText().orEmpty()
                    if (resp.code != 200) {
                        throw GuardianHttpError(
                            "failed to activate guardian entitlement: HTTP ${resp.code}",
                            resp.code,
                            retryAfterMillis(resp.header("Retry-After")),
                        )
                    }
                    parseEntitlement(JSONObject(text))
                }
            }
        }

    private fun parseEntitlement(body: JSONObject): Entitlement {
        val max = when {
            body.has("maxBytes") && !body.isNull("maxBytes") -> body.optLong("maxBytes", -1L).takeIf { it >= 0 }
            body.has("max_bytes") && !body.isNull("max_bytes") -> body.optLong("max_bytes", -1L).takeIf { it >= 0 }
            body.has("quota_limit") && !body.isNull("quota_limit") -> body.optLong("quota_limit", -1L).takeIf { it >= 0 }
            else -> null
        }
        val remaining = when {
            body.has("quota_remaining") && !body.isNull("quota_remaining") -> body.optLong("quota_remaining", -1L).takeIf { it >= 0 }
            body.has("quotaRemaining") && !body.isNull("quotaRemaining") -> body.optLong("quotaRemaining", -1L).takeIf { it >= 0 }
            else -> null
        }
        val limited = body.optBoolean("limited_bandwidth", body.optBoolean("limitedBandwidth", max != null || remaining != null))
        return Entitlement(
            maxBytes = max,
            limitedBandwidth = limited,
            quotaRemaining = remaining,
        )
    }

    private suspend fun authorizedRequest(
        method: String,
        url: String,
        accessToken: String,
    ): Response {
        val origin = url.toHttpUrl().let { "${it.scheme}://${it.host}" }

        fun buildRequest(): Request {
            val builder =
                Request.Builder()
                    .url(url)
                    .header("User-Agent", GUARDIAN_USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Cache-Control", "max-age=0")
                    .header("Authorization", "Bearer $accessToken")
            if (method == "POST") builder.post(ByteArray(0).toRequestBody(null))
            return builder.build()
        }
        var response = ControlPlaneHttp.client.newCall(buildRequest()).awaitResponse()
        var attempts = 1
        while (response.code == 406 && attempts < GUARDIAN_MAX_CHALLENGE_ATTEMPTS) {
            val challengedBody = response.use { it.body?.readBoundedText().orEmpty() }
            try {
                challengeSolver.solveAndInstall(challengedBody, origin, GUARDIAN_USER_AGENT)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (solveFailure: Exception) {
                AppLogger.w(TAG, "could not solve the Fastly challenge", solveFailure)
                throw GuardianHttpError(
                    "Mozilla's bot protection rejected this request (HTTP 406) and the challenge " +
                        "could not be solved automatically. Try again later or on another network.",
                    406,
                )
            }
            response = ControlPlaneHttp.client.newCall(buildRequest()).awaitResponse()
            attempts++
        }
        return response
    }
}

internal fun jwtExpiryEpochSeconds(token: String): Long? {
    val parts = token.split('.')
    if (parts.size < 2) return null
    val payload =
        runCatching {
                var b64 = parts[1]
                while (b64.length % 4 != 0) b64 += "="
                String(
                    android.util.Base64.decode(
                        b64,
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
                    ),
                    Charsets.UTF_8,
                )
            }
            .getOrNull() ?: return null
    return runCatching { JSONObject(payload).optLong("exp", 0L) }.getOrNull()?.takeIf { it > 0 }
}
