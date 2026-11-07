package com.vauth.foxyvpn.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal object FirefoxAccountHeaders {
    private const val PRODUCT_DETAILS_URL =
        "https://product-details.mozilla.org/1.0/firefox_versions.json"
    private val mutex = Mutex()
    @Volatile private var major = "159"
    private var nextRefreshNanos: Long? = null

    val controlPlaneUserAgent: String
        get() = FoxyRemoteConfig.current.controlPlane.headers["User-Agent"]
            ?: resolvePlaceholders("MozillaVPN/2.24.0 (sys:android {android_version})")

    val browserUserAgent: String
        get() = FoxyRemoteConfig.current.browser.headers["User-Agent"]
            ?: resolvePlaceholders("Mozilla/5.0 (Android {android_version}; Mobile; rv:$major.0) Gecko/$major.0 Firefox/$major.0")

    val desktopUserAgent: String
        get() = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:$major.0) Gecko/20100101 Firefox/$major.0"

    val userAgent: String
        get() = controlPlaneUserAgent

    fun apply(builder: Request.Builder): Request.Builder {
        val headers = FoxyRemoteConfig.current.controlPlane.headers
        for ((key, value) in headers) {
            builder.header(key, value)
        }
        return builder
    }

    suspend fun refresh(client: OkHttpClient) {
        mutex.withLock {
            nextRefreshNanos?.let { if (System.nanoTime() - it < 0L) return }
            val refreshed =
                try {
                    withTimeoutOrNull(5_000L) {
                        val request =
                            apply(Request.Builder().url(PRODUCT_DETAILS_URL)).get().build()
                        client.newCall(request).awaitResponse().use { response ->
                            if (!response.isSuccessful) return@withTimeoutOrNull false
                            val text =
                                response.body?.readBoundedText(65_536)
                                    ?: return@withTimeoutOrNull false
                            val version =
                                JSONObject(text).optString("LATEST_FIREFOX_VERSION").trim()
                            val candidate = version.substringBefore('.')
                            if (!candidate.matches(Regex("[0-9]{1,4}")))
                                return@withTimeoutOrNull false
                            major = candidate
                            true
                        }
                    } ?: false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            nextRefreshNanos =
                System.nanoTime() + TimeUnit.HOURS.toNanos(if (refreshed) 24L else 1L)
        }
    }
}
