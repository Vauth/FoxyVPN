package com.vauth.foxyvpn.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vauth.foxyvpn.data.ProxyStateStore
import com.vauth.foxyvpn.data.RECOMMENDED_COUNTRY_CODE
import com.vauth.foxyvpn.data.ServerListClient
import com.vauth.foxyvpn.data.model.ProxyCandidate
import com.vauth.foxyvpn.data.model.VpnCountry
import com.vauth.foxyvpn.vpn.PingUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed class LocationRow {
    data class RecommendedRow(val country: VpnCountry) : LocationRow()
    data class CountryHeader(val country: VpnCountry) : LocationRow()
    data class CityRow(val country: VpnCountry, val cityIndex: Int) : LocationRow()
}

@Composable
fun ServerListScreen(
    serverListClient: ServerListClient,
    proxyStateStore: ProxyStateStore,
    onServerSelected: () -> Unit,
    onBack: () -> Unit,
) {
    var countries by remember { mutableStateOf<List<VpnCountry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val selectedState by produceState<ProxyCandidate?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { proxyStateStore.load() }
    }

    LaunchedEffect(Unit) {
        runCatching { serverListClient.fetchCountries() }
            .onSuccess { countries = it }
            .onFailure { errorMessage = it.message ?: "Failed to load server list" }
        isLoading = false
    }

    val ordered = remember(countries) {
        countries.sortedWith(compareBy({ it.code != RECOMMENDED_COUNTRY_CODE }, { it.name }))
    }

    val rows = remember(ordered) {
        buildList {
            for (country in ordered) {
                if (country.code == RECOMMENDED_COUNTRY_CODE) {
                    add(LocationRow.RecommendedRow(country))
                    continue
                }
                add(LocationRow.CountryHeader(country))
                country.cities.indices.forEach { index -> add(LocationRow.CityRow(country, index)) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose location") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                errorMessage != null && countries.isEmpty() -> Text(
                    errorMessage.orEmpty(),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> LazyColumn {
                    items(rows) { row ->

                        val selected = selectedState
                        when (row) {
                            is LocationRow.RecommendedRow -> ListItem(
                                headlineContent = { Text("Recommended", fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text("Automatically chosen for the best performance") },
                                trailingContent = {
                                    if (row.country.code == selected?.countryCode) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = "Selected")
                                    }
                                },
                                modifier = Modifier
                                    .padding(horizontal = 8.dp)
                                    .clickable {
                                        val candidates = ServerListClient.candidatesForCountry(countries, row.country.code)
                                        val chosen = candidates.randomOrNull()
                                        if (chosen != null) {
                                            proxyStateStore.save(chosen)
                                            onServerSelected()
                                        } else {
                                            errorMessage = "No available servers for ${row.country.name}"
                                        }
                                    },
                            )
                            is LocationRow.CountryHeader -> ListItem(
                                headlineContent = { Text(row.country.name, fontWeight = FontWeight.SemiBold) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            is LocationRow.CityRow -> {
                                val city = row.country.cities[row.cityIndex]
                                val serverCount = city.servers.size

                                var pingMs by remember(row.country.code, city.code) { mutableStateOf<Int?>(null) }
                                var pingFailed by remember(row.country.code, city.code) { mutableStateOf(false) }
                                LaunchedEffect(row.country.code, city.code) {
                                    val target = city.servers.firstOrNull { !it.quarantined }
                                        ?.let { ServerListClient.defaultConnectTarget(it) }
                                    if (target == null) {
                                        pingFailed = true
                                        return@LaunchedEffect
                                    }
                                    val result = PingUtil.measureTcpLatencyMs(target.first, target.second)
                                    if (result != null) pingMs = result else pingFailed = true
                                }

                                ListItem(

                                    headlineContent = { Text(city.code) },
                                    supportingContent = {
                                        val primaryHostname = city.servers.firstOrNull()?.hostname
                                        val serverLabel = when {
                                            primaryHostname == null -> "No servers available"
                                            serverCount > 1 -> "$primaryHostname (+${serverCount - 1} more)"
                                            else -> primaryHostname
                                        }
                                        val pingLabel = when {
                                            pingMs != null -> "$pingMs ms"
                                            pingFailed -> "ping unavailable"
                                            else -> "pinging\u2026"
                                        }
                                        Text("$serverLabel \u2022 $pingLabel")
                                    },
                                    trailingContent = {
                                        if (row.country.code == selected?.countryCode &&
                                            city.code == selected?.cityCode
                                        ) {
                                            Icon(Icons.Filled.CheckCircle, contentDescription = "Selected")
                                        }
                                    },
                                    modifier = Modifier
                                        .padding(start = 24.dp, end = 8.dp)
                                        .clickable {
                                            val candidates = ServerListClient.candidatesForCity(countries, row.country.code, city.code)
                                            val chosen = candidates.randomOrNull()
                                            if (chosen != null) {
                                                proxyStateStore.save(chosen)
                                                onServerSelected()
                                            } else {
                                                errorMessage = "No available servers in ${city.code}"
                                            }
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
