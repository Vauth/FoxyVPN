package com.vauth.foxyvpn.data

import android.content.Context
import com.vauth.foxyvpn.data.model.ProxyCandidate
import com.vauth.foxyvpn.data.model.VpnCity
import com.vauth.foxyvpn.data.model.VpnCountry
import com.vauth.foxyvpn.data.model.VpnProtocol
import com.vauth.foxyvpn.data.model.VpnServerNode
import com.vauth.foxyvpn.data.model.displayName
import com.vauth.foxyvpn.data.model.normalizeCountryName
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ServerListClient"

private const val REMOTE_SETTINGS_URL =
    "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records"

const val RECOMMENDED_COUNTRY_CODE = com.vauth.foxyvpn.data.model.RECOMMENDED_COUNTRY_CODE

private val EXCLUDED_COUNTRY_NAMES = setOf("CatchAll Anycast")

class ServerListClient(
    private val context: Context? = null,
    private val client: OkHttpClient = ControlPlaneHttp.client,
) {

    private val cacheFile: File?
        get() = context?.let { File(it.filesDir, "vpn_serverlist_cache.json") }

    fun hasFreshCache(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val modified = cacheFile?.takeIf { it.isFile }?.lastModified() ?: return false
        return modified > 0L && nowMillis - modified in 0 until 6 * 60 * 60_000L
    }

    suspend fun fetchCountries(): List<VpnCountry> =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder().url(REMOTE_SETTINGS_URL).applyFirefoxHeaders().build()
            client.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Remote Settings fetch failed: HTTP ${response.code}")
                }
                val raw = response.body?.readBoundedText(4 * 1024 * 1024).orEmpty()
                val countries = parseRecords(raw)
                if (countries.isEmpty()) throw IOException("Server directory contains no locations")
                saveCache(raw)
                AppLogger.i(TAG, "fetched ${countries.size} countries from Remote Settings")
                countries
            }
        }

    fun loadCachedCountries(): List<VpnCountry> {
        val file = cacheFile ?: return emptyList()
        if (!file.exists() || !file.isFile) return emptyList()
        return runCatching {
                val raw = file.inputStream().use { it.readBoundedUtf8(4 * 1024 * 1024) }
                val parsed = parseRecords(raw)
                if (parsed.isNotEmpty()) {
                    AppLogger.d(TAG, "loaded ${parsed.size} countries from local cache")
                }
                parsed
            }
            .getOrElse {
                AppLogger.w(TAG, "failed to read server list cache", it)
                emptyList()
            }
    }

    private fun saveCache(rawJson: String) {
        val file = cacheFile ?: return
        runCatching {
                val tempFile = File.createTempFile("serverlist", ".tmp", file.parentFile)
                try {
                    tempFile.writeText(rawJson)
                    if (!tempFile.renameTo(file))
                        throw IOException("Could not atomically replace server directory cache")
                } finally {
                    tempFile.delete()
                }
                AppLogger.d(TAG, "updated local server list cache")
            }
            .onFailure { AppLogger.w(TAG, "failed to save server list cache", it) }
    }

    private fun parseRecords(rawJson: String): List<VpnCountry> {
        if (rawJson.isBlank()) return emptyList()
        val body = JSONObject(rawJson)
        val records = body.optJSONArray("data") ?: JSONArray()
        val countries = mutableListOf<VpnCountry>()
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            val countryJson = record.optJSONObject("country") ?: record
            val country = parseCountry(countryJson)
            val isExcluded =
                EXCLUDED_COUNTRY_NAMES.any { it.equals(country.name.trim(), ignoreCase = true) }
            if (country.code.isNotBlank() && country.cities.isNotEmpty() && !isExcluded) {
                countries.add(country)
            }
        }
        return countries
            .groupBy { it.code }
            .values
            .map { locations ->
                val first = locations.first()
                first.copy(
                    cities =
                        locations
                            .flatMap { it.cities }
                            .groupBy { it.code }
                            .values
                            .map { cities ->
                                cities
                                    .first()
                                    .copy(
                                        servers =
                                            cities
                                                .flatMap { it.servers }
                                                .groupBy {
                                                    it.hostname.lowercase(java.util.Locale.ROOT) to
                                                        it.port
                                                }
                                                .values
                                                .map { nodes ->
                                                    nodes
                                                        .first()
                                                        .copy(
                                                            quarantined =
                                                                nodes.any { it.quarantined },
                                                            protocols =
                                                                nodes
                                                                    .flatMap { it.protocols }
                                                                    .distinct(),
                                                        )
                                                }
                                    )
                            }
                )
            }
    }

    private fun parseCountry(json: JSONObject): VpnCountry {
        val code = json.optString("code").trim().uppercase(java.util.Locale.ROOT)
        val rawName = json.optString("name")
        val name = normalizeCountryName(code, rawName)
        return VpnCountry(
            name = name,
            code = code,
            cities =
                buildList {
                    val cities = json.optJSONArray("cities") ?: JSONArray()
                    for (i in 0 until cities.length()) {
                        val city = cities.optJSONObject(i) ?: continue
                        add(parseCity(city))
                    }
                },
        )
    }

    private fun parseCity(json: JSONObject): VpnCity =
        VpnCity(
            name = json.optString("name"),
            code = json.optString("code").trim().uppercase(java.util.Locale.ROOT),
            servers =
                buildList {
                    val servers = json.optJSONArray("servers") ?: JSONArray()
                    for (i in 0 until servers.length()) {
                        val server = servers.optJSONObject(i) ?: continue
                        add(parseServer(server))
                    }
                },
        )

    private fun parseServer(json: JSONObject): VpnServerNode =
        VpnServerNode(
            hostname = json.optString("hostname"),
            port = json.optInt("port", 0),
            quarantined = json.optBoolean("quarantined", false),
            protocols =
                buildList {
                    val protocols = json.optJSONArray("protocols") ?: JSONArray()
                    for (i in 0 until protocols.length()) {
                        val protocol = protocols.optJSONObject(i) ?: continue
                        add(parseProtocol(protocol))
                    }
                },
        )

    private fun parseProtocol(json: JSONObject): VpnProtocol =
        VpnProtocol(
            name = json.optString("name"),
            host = json.optString("host"),
            port = json.optInt("port", 0),
        )

    companion object {

        fun defaultConnectTarget(server: VpnServerNode): Pair<String, Int>? {
            for (proto in server.protocols) {
                if (proto.name == "connect") {
                    val host = proto.host.ifBlank { server.hostname }
                    val port = if (proto.port != 0) proto.port else server.port
                    if (SettingsStore.isValidEdgeHost(host) && port in 1..65_535)
                        return host to port
                }
            }
            if (server.protocols.isEmpty())
                return (server.hostname to server.port).takeIf {
                    SettingsStore.isValidEdgeHost(server.hostname) && server.port in 1..65_535
                }
            return null
        }

        fun candidatesForCountry(
            countries: List<VpnCountry>,
            countryCode: String,
        ): List<ProxyCandidate> {
            val out = mutableListOf<ProxyCandidate>()
            for (country in countries) {
                if (!country.code.equals(countryCode, ignoreCase = true)) continue
                for (city in country.cities) {
                    for (server in city.servers) {
                        if (server.quarantined) continue
                        val target = defaultConnectTarget(server) ?: continue
                        val countryDisplayName = country.displayName
                        out.add(
                            ProxyCandidate(
                                host = target.first,
                                port = target.second,
                                countryCode = country.code,
                                countryName = countryDisplayName,
                                cityCode = city.code,
                            )
                        )
                    }
                }
            }
            return out
        }

        fun candidatesForCity(
            countries: List<VpnCountry>,
            countryCode: String,
            cityCode: String,
        ): List<ProxyCandidate> =
            candidatesForCountry(countries, countryCode).filter {
                it.cityCode.equals(cityCode, ignoreCase = true)
            }
    }
}
