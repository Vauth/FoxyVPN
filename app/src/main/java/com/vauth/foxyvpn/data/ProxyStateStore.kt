package com.vauth.foxyvpn.data

import android.content.Context
import com.vauth.foxyvpn.data.model.ProxyCandidate
import com.vauth.foxyvpn.data.model.displayName
import com.vauth.foxyvpn.data.model.normalizeCountryName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ProxyStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("foxyvpn_proxy_state", Context.MODE_PRIVATE)

    private val _selectedProxyFlow = MutableStateFlow<ProxyCandidate?>(load())
    val selectedProxyFlow: StateFlow<ProxyCandidate?> = _selectedProxyFlow

    @Synchronized
    fun load(): ProxyCandidate? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val port = prefs.getInt(KEY_PORT, 0)
        if (port !in 1..65_535 || !SettingsStore.isValidEdgeHost(host)) return null
        val code = prefs.getString(KEY_COUNTRY_CODE, "").orEmpty()
        val rawName = prefs.getString(KEY_COUNTRY_NAME, "").orEmpty()
        val name = normalizeCountryName(code, rawName)
        return ProxyCandidate(
            host = host,
            port = port,
            countryCode = code,
            countryName = name,
            cityCode = prefs.getString(KEY_CITY_CODE, "").orEmpty(),
        )
    }

    @Synchronized
    fun save(candidate: ProxyCandidate) {
        require(candidate.port in 1..65_535 && SettingsStore.isValidEdgeHost(candidate.host)) {
            "Invalid server address"
        }
        val name = candidate.displayName
        prefs
            .edit()
            .putString(KEY_HOST, candidate.host)
            .putInt(KEY_PORT, candidate.port)
            .putString(KEY_COUNTRY_CODE, candidate.countryCode)
            .putString(KEY_COUNTRY_NAME, name)
            .putString(KEY_CITY_CODE, candidate.cityCode)
            .apply()
        _selectedProxyFlow.value = candidate.copy(countryName = name)
    }

    @Synchronized
    fun saveIfUnchanged(expected: ProxyCandidate?, candidate: ProxyCandidate): Boolean {
        if (load() != expected) return false
        save(candidate)
        return true
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        _selectedProxyFlow.value = null
    }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_COUNTRY_CODE = "country_code"
        private const val KEY_COUNTRY_NAME = "country_name"
        private const val KEY_CITY_CODE = "city_code"
    }
}
