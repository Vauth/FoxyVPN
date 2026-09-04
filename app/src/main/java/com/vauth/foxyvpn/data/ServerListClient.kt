package com.vauth.foxyvpn.data

import com.vauth.foxyvpn.data.model.ProxyCandidate
import com.vauth.foxyvpn.data.model.VpnCity
import com.vauth.foxyvpn.data.model.VpnCountry
import com.vauth.foxyvpn.data.model.VpnProtocol
import com.vauth.foxyvpn.data.model.VpnServerNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private const val TAG = "ServerListClient"

private const val REMOTE_SETTINGS_URL =
    "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records"

const val RECOMMENDED_COUNTRY_CODE = "REC"

private val EXCLUDED_COUNTRY_NAMES = setOf("CatchAll Anycast")

class ServerListClient(private val client: OkHttpClient = ControlPlaneHttp.client) {

    suspend fun fetchCountries(): List<VpnCountry> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(REMOTE_SETTINGS_URL).applyMozillaVpnHeaders().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Remote Settings fetch failed: HTTP ${response.code}")
            }
            val body = JSONObject(response.body?.string().orEmpty())
            val records = body.optJSONArray("data") ?: JSONArray()
            val countries = mutableListOf<VpnCountry>()
            for (i in 0 until records.length()) {
                val record = records.getJSONObject(i)
                val countryJson = record.optJSONObject("country") ?: record
                val country = parseCountry(countryJson)
                val isExcluded = EXCLUDED_COUNTRY_NAMES.any { it.equals(country.name, ignoreCase = true) }
                if (country.code.isNotBlank() && country.cities.isNotEmpty() && !isExcluded) {
                    countries.add(country)
                }
            }
            AppLogger.i(TAG, "fetched ${countries.size} countries from Remote Settings")
            countries
        }
    }

    private fun parseCountry(json: JSONObject): VpnCountry = VpnCountry(
        name = json.optString("name"),
        code = json.optString("code"),
        cities = buildList {
            val cities = json.optJSONArray("cities") ?: JSONArray()
            for (i in 0 until cities.length()) add(parseCity(cities.getJSONObject(i)))
        },
    )

    private fun parseCity(json: JSONObject): VpnCity = VpnCity(
        name = json.optString("name"),
        code = json.optString("code"),
        servers = buildList {
            val servers = json.optJSONArray("servers") ?: JSONArray()
            for (i in 0 until servers.length()) add(parseServer(servers.getJSONObject(i)))
        },
    )

    private fun parseServer(json: JSONObject): VpnServerNode = VpnServerNode(
        hostname = json.optString("hostname"),
        port = json.optInt("port", 0),
        quarantined = json.optBoolean("quarantined", false),
        protocols = buildList {
            val protocols = json.optJSONArray("protocols") ?: JSONArray()
            for (i in 0 until protocols.length()) add(parseProtocol(protocols.getJSONObject(i)))
        },
    )

    private fun parseProtocol(json: JSONObject): VpnProtocol = VpnProtocol(
        name = json.optString("name"),
        host = json.optString("host"),
        port = json.optInt("port", 0),
        scheme = json.optString("scheme"),
        templateString = json.optString("templateString"),
    )

    companion object {

        fun defaultConnectTarget(server: VpnServerNode): Pair<String, Int>? {
            for (proto in server.protocols) {
                if (proto.name == "connect") {
                    val host = proto.host.ifBlank { server.hostname }
                    val port = if (proto.port != 0) proto.port else server.port
                    return host to port
                }
            }
            if (server.protocols.isEmpty()) return server.hostname to server.port
            return null
        }

        fun candidatesForCountry(countries: List<VpnCountry>, countryCode: String): List<ProxyCandidate> {
            val out = mutableListOf<ProxyCandidate>()
            for (country in countries) {
                if (!country.code.equals(countryCode, ignoreCase = true)) continue
                for (city in country.cities) {
                    for (server in city.servers) {
                        if (server.quarantined) continue
                        val target = defaultConnectTarget(server) ?: continue
                        out.add(
                            ProxyCandidate(
                                host = target.first,
                                port = target.second,
                                countryCode = country.code,
                                countryName = country.name,
                                cityCode = city.code,
                            ),
                        )
                    }
                }
            }
            return out
        }

        fun candidatesForCity(countries: List<VpnCountry>, countryCode: String, cityCode: String): List<ProxyCandidate> =
            candidatesForCountry(countries, countryCode).filter { it.cityCode.equals(cityCode, ignoreCase = true) }
    }
}
