package com.vauth.foxyvpn.data

import android.content.Context
import com.vauth.foxyvpn.data.model.ProxyCandidate

class ProxyStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("foxyvpn_proxy_state", Context.MODE_PRIVATE)

    fun load(): ProxyCandidate? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val port = prefs.getInt(KEY_PORT, 0)
        if (port == 0) return null
        return ProxyCandidate(
            host = host,
            port = port,
            countryCode = prefs.getString(KEY_COUNTRY_CODE, "").orEmpty(),
            countryName = prefs.getString(KEY_COUNTRY_NAME, "").orEmpty(),
            cityCode = prefs.getString(KEY_CITY_CODE, "").orEmpty(),
        )
    }

    fun save(candidate: ProxyCandidate) {
        prefs.edit()
            .putString(KEY_HOST, candidate.host)
            .putInt(KEY_PORT, candidate.port)
            .putString(KEY_COUNTRY_CODE, candidate.countryCode)
            .putString(KEY_COUNTRY_NAME, candidate.countryName)
            .putString(KEY_CITY_CODE, candidate.cityCode)
            .putInt(KEY_FAILURES, 0)
            .apply()
    }

    fun recordFailure(): Pair<Int, Boolean> {
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        if (failures >= FAILURE_THRESHOLD) {
            prefs.edit().clear().apply()
            return failures to true
        }
        prefs.edit().putInt(KEY_FAILURES, failures).apply()
        return failures to false
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_COUNTRY_CODE = "country_code"
        private const val KEY_COUNTRY_NAME = "country_name"
        private const val KEY_CITY_CODE = "city_code"
        private const val KEY_FAILURES = "failures"
        private const val FAILURE_THRESHOLD = 3
    }
}
