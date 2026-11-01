package com.vauth.foxyvpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.Configuration
import com.vauth.foxyvpn.data.CrashReporter
import com.vauth.foxyvpn.data.FxaAuthRepository
import com.vauth.foxyvpn.data.GuardianClient
import com.vauth.foxyvpn.data.ProxyStateStore
import com.vauth.foxyvpn.data.ServerListClient
import com.vauth.foxyvpn.data.SettingsStore
import com.vauth.foxyvpn.data.TokenStore
import com.vauth.foxyvpn.update.AppUpdater
import com.vauth.foxyvpn.vpn.FoxyVpnService
import com.vauth.foxyvpn.widget.QuickConnectWidgetProvider

interface AppContainer {
    val tokenStore: TokenStore
    val proxyStateStore: ProxyStateStore
    val settingsStore: SettingsStore
    val guardianClient: GuardianClient
    val serverListClient: ServerListClient
    val authRepository: FxaAuthRepository
}

class FoxyVpnApp : Application(), AppContainer {

    internal lateinit var appUpdater: AppUpdater
    override lateinit var tokenStore: TokenStore
        internal set
    override lateinit var proxyStateStore: ProxyStateStore
        internal set
    override lateinit var settingsStore: SettingsStore
        internal set
    override lateinit var guardianClient: GuardianClient
        internal set
    override lateinit var serverListClient: ServerListClient
        internal set
    override lateinit var authRepository: FxaAuthRepository
        internal set

    override fun onCreate() {
        super.onCreate()

        CrashReporter.install(this)
        CrashReporter.replayLastCrashIfAny(this)

        appUpdater = AppUpdater(this)
        tokenStore = TokenStore(this)
        proxyStateStore = ProxyStateStore(this)
        settingsStore = SettingsStore(this)
        guardianClient = GuardianClient()
        serverListClient = ServerListClient(this)
        authRepository = FxaAuthRepository(tokenStore)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                VPN_NOTIFICATION_CHANNEL_ID,
                "VPN status",
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        QuickConnectWidgetProvider.updateAll(this, FoxyVpnService.state.value)
    }

    companion object {
        const val VPN_NOTIFICATION_CHANNEL_ID = "foxyvpn_status"
    }
}
