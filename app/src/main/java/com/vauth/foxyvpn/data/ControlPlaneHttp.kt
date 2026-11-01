package com.vauth.foxyvpn.data

import android.net.Network
import android.webkit.CookieManager
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

fun Request.Builder.applyFirefoxHeaders(): Request.Builder = FirefoxAccountHeaders.apply(this)

class SimpleCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies.add(cookie)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies
            .filter { it.matches(url) }
            .sortedByDescending { it.domain.length }
            .distinctBy { it.name }
    }

    @Synchronized fun snapshotAll(): List<Cookie> = cookies.toList()

    @Synchronized
    fun clear() {
        cookies.clear()
    }

    @Synchronized
    fun syncFromWebView(
        urls: List<String> =
            listOf(
                "https://accounts.firefox.com",
                "https://api.accounts.firefox.com",
                "https://oauth.accounts.firefox.com",
                "https://vpn.mozilla.org",
            ),
    ) {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        for (urlStr in urls) {
            val httpUrl = urlStr.toHttpUrlOrNull() ?: continue
            val cookieHeader = runCatching { manager.getCookie(urlStr) }.getOrNull() ?: continue
            val parsedCookies = mutableListOf<Cookie>()
            for (raw in cookieHeader.split(";")) {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) continue
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx <= 0) continue
                val name = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                if (name.isEmpty() || value.isEmpty()) continue

                val hostCookie =
                    runCatching {
                        Cookie.Builder()
                            .name(name)
                            .value(value)
                            .domain(httpUrl.host)
                            .path("/")
                            .apply { if (httpUrl.isHttps) secure() }
                            .build()
                    }.getOrNull()
                if (hostCookie != null) parsedCookies.add(hostCookie)

                if (httpUrl.host.endsWith("firefox.com") && httpUrl.host != "firefox.com") {
                    val domainCookie =
                        runCatching {
                            Cookie.Builder()
                                .name(name)
                                .value(value)
                                .domain("firefox.com")
                                .path("/")
                                .apply { if (httpUrl.isHttps) secure() }
                                .build()
                        }.getOrNull()
                    if (domainCookie != null) parsedCookies.add(domainCookie)
                    for (target in listOf("api.accounts.firefox.com", "oauth.accounts.firefox.com", "vpn.mozilla.org")) {
                        val targetCookie =
                            runCatching {
                                Cookie.Builder()
                                    .name(name)
                                    .value(value)
                                    .domain(target)
                                    .path("/")
                                    .apply { if (httpUrl.isHttps) secure() }
                                    .build()
                            }.getOrNull()
                        if (targetCookie != null) parsedCookies.add(targetCookie)
                    }
                }
            }
            if (parsedCookies.isNotEmpty()) {
                saveFromResponse(httpUrl, parsedCookies)
            }
        }
    }

    @Synchronized
    fun syncToWebView(cookies: List<Cookie>) {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        for (cookie in cookies) {
            val host = cookie.domain.trimStart('.')
            if (host.isEmpty()) continue
            val urlStr = "https://$host"
            val cookieString = StringBuilder().apply {
                append(cookie.name).append("=").append(cookie.value)
                append("; Domain=").append(cookie.domain)
                append("; Path=").append(cookie.path)
                if (cookie.secure) append("; Secure")
                if (cookie.httpOnly) append("; HttpOnly")
            }.toString()
            runCatching { manager.setCookie(urlStr, cookieString) }
        }
        runCatching { manager.flush() }
    }
}

private class ProtectingSocketFactory : SocketFactory() {

    private fun createProtectedSocket(
        localAddress: InetSocketAddress? = null,
        remoteAddress: InetSocketAddress? = null,
    ): Socket {
        val socket = Socket()
        try {
            socket.bind(localAddress ?: InetSocketAddress(0))
            val protector = ControlPlaneHttp.socketProtector
            if (protector != null && !protector(socket)) {
                throw java.io.IOException("VPN refused control-plane socket protection")
            }
            ControlPlaneHttp.underlyingNetwork()?.bindSocket(socket)
            if (remoteAddress != null) socket.connect(remoteAddress)
            return socket
        } catch (error: Exception) {
            runCatching { socket.close() }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }

    override fun createSocket(): Socket = createProtectedSocket()

    override fun createSocket(host: String, port: Int): Socket =
        createProtectedSocket(remoteAddress = InetSocketAddress(host, port))

    override fun createSocket(
        host: String,
        port: Int,
        localAddr: InetAddress,
        localPort: Int,
    ): Socket =
        createProtectedSocket(
            InetSocketAddress(localAddr, localPort),
            InetSocketAddress(host, port),
        )

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createProtectedSocket(remoteAddress = InetSocketAddress(host, port))

    override fun createSocket(
        host: InetAddress,
        port: Int,
        localAddr: InetAddress,
        localPort: Int,
    ): Socket =
        createProtectedSocket(
            InetSocketAddress(localAddr, localPort),
            InetSocketAddress(host, port),
        )
}

object ControlPlaneHttp {
    val cookieJar = SimpleCookieJar()

    @Volatile var socketProtector: ((Socket) -> Boolean)? = null

    @Volatile var networkProvider: (() -> Network?)? = null

    internal fun underlyingNetwork(): Network? {
        val provider = networkProvider ?: return null
        return provider() ?: throw java.io.IOException("No underlying network is available")
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .cookieJar(cookieJar)
            .socketFactory(ProtectingSocketFactory())
            .dns(
                object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        val network = runCatching { underlyingNetwork() }.getOrNull()
                        val fromNetwork =
                            if (network != null) {
                                runCatching { network.getAllByName(hostname).toList() }.getOrNull()
                            } else {
                                null
                            }
                        return if (!fromNetwork.isNullOrEmpty()) {
                            fromNetwork
                        } else {
                            okhttp3.Dns.SYSTEM.lookup(hostname)
                        }
                    }
                }
            )
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
