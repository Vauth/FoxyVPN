package com.vauth.foxyvpn.vpn.tun

import com.vauth.foxyvpn.data.AppLogger

private const val TAG = "HevSocks5Tunnel"

object HevSocks5Tunnel {
    private external fun TProxyStartService(config_path: String, fd: Int): Boolean
    private external fun TProxyStopService(): Boolean
    private external fun TProxyIsRunning(): Boolean
    private external fun TProxyGetStats(): LongArray

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    fun start(configPath: String, tunFd: Int): Boolean =
        runCatching { TProxyStartService(configPath, tunFd) }
            .onFailure { AppLogger.e(TAG, "failed to start hev-socks5-tunnel", it) }
            .getOrDefault(false)

    fun stop(): Boolean =
        runCatching { TProxyStopService() }
            .onFailure { AppLogger.w(TAG, "failed to stop hev-socks5-tunnel", it) }
            .getOrDefault(false)

    fun isRunning(): Boolean = runCatching { TProxyIsRunning() }.getOrDefault(false)

    fun stats(): LongArray = runCatching { TProxyGetStats() }.getOrDefault(LongArray(4))
}
