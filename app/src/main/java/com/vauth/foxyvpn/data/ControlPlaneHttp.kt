package com.vauth.foxyvpn.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

const val MOZILLA_VPN_USER_AGENT = "MozillaVPN/2.35.0 (sys:linux; iap:true)"

fun Request.Builder.applyMozillaVpnHeaders(): Request.Builder {
    header("User-Agent", MOZILLA_VPN_USER_AGENT)
    header("Accept", "application/json")
    return this
}

class SimpleCookieJar : CookieJar {
    private val byDomain = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            val bucket = byDomain.getOrPut(cookie.domain) { mutableListOf() }
            synchronized(bucket) {
                bucket.removeAll { it.name == cookie.name }
                bucket.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val result = mutableListOf<Cookie>()
        for ((domain, cookies) in byDomain) {
            if (url.host == domain || url.host.endsWith(".$domain")) {
                synchronized(cookies) {
                    result.addAll(cookies.filter { it.expiresAt > now })
                }
            }
        }
        return result
    }

    fun set(name: String, value: String, domain: String) {
        val bucket = byDomain.getOrPut(domain) { mutableListOf() }
        synchronized(bucket) {
            bucket.removeAll { it.name == name }
            bucket.add(
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(domain)
                    .path("/")
                    .expiresAt(Long.MAX_VALUE)
                    .build(),
            )
        }
    }

    fun snapshotAll(): List<Cookie> = byDomain.values.flatten()
}

private class ProtectingSocketFactory : SocketFactory() {
    private fun protect(socket: Socket): Socket {
        ControlPlaneHttp.socketProtector?.invoke(socket)
        return socket
    }

    override fun createSocket(): Socket = protect(Socket())

    override fun createSocket(host: String, port: Int): Socket =
        protect(Socket()).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localAddr: InetAddress, localPort: Int): Socket =
        protect(Socket()).apply {
            bind(InetSocketAddress(localAddr, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        protect(Socket()).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: InetAddress, port: Int, localAddr: InetAddress, localPort: Int): Socket =
        protect(Socket()).apply {
            bind(InetSocketAddress(localAddr, localPort))
            connect(InetSocketAddress(host, port))
        }
}

object ControlPlaneHttp {
    val cookieJar = SimpleCookieJar()

    @Volatile
    var socketProtector: ((Socket) -> Boolean)? = null

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .socketFactory(ProtectingSocketFactory())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
