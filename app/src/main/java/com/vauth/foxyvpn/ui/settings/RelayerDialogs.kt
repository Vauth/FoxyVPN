package com.vauth.foxyvpn.ui.settings

import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vauth.foxyvpn.R
import com.vauth.foxyvpn.data.ControlPlaneHttp
import com.vauth.foxyvpn.data.FoxyRemoteConfig
import com.vauth.foxyvpn.data.SettingsStore
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

private const val MAX_SAVED_RELAYER_DRAFT_CHARS = 4096

private val RelayerDraftSaver =
    Saver<String, String>(
        save = { it.takeIf { value -> value.length <= MAX_SAVED_RELAYER_DRAFT_CHARS } },
        restore = { it },
    )

private val EndpointSelectionSaver =
    listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

private sealed interface RelayerProbeState {
    data object Idle : RelayerProbeState
    data object Testing : RelayerProbeState
    data class Success(val ms: Int) : RelayerProbeState
    data object Failed : RelayerProbeState
}

private suspend fun probeRelayerEndpoint(targetRelayerUrl: String?): RelayerProbeState =
    withContext(Dispatchers.IO) {
        val client = ControlPlaneHttp.client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
        try {
            val probeTarget = FoxyRemoteConfig.current.endpoints.latencyProbe.ifBlank { "https://1.1.1.1" }
            val requestUrl = if (targetRelayerUrl != null) {
                val b64 = Base64.encodeToString(
                    probeTarget.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP,
                ).trim()
                val rawBase = targetRelayerUrl.trim()
                val base = if (rawBase.startsWith("http://", ignoreCase = true) || rawBase.startsWith("https://", ignoreCase = true)) {
                    rawBase
                } else {
                    "https://$rawBase"
                }
                when {
                    base.endsWith("?url=") -> "$base$b64"
                    base.endsWith("&url=") -> "$base$b64"
                    base.contains("?") -> "${base}&url=$b64"
                    else -> "${base.trimEnd('/')}/?url=$b64"
                }
            } else {
                probeTarget
            }
            val request = Request.Builder()
                .url(requestUrl)
                .header("Cache-Control", "no-cache, no-store")
                .build()
            val start = System.nanoTime()
            client.newCall(request).execute().use { response ->
                if (response.code in 200..499) {
                    val ms = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L).toInt()
                    RelayerProbeState.Success(ms)
                } else {
                    RelayerProbeState.Failed
                }
            }
        } catch (_: Exception) {
            RelayerProbeState.Failed
        }
    }

private data class RelayerProviderOption(
    val key: String,
    val title: String,
    val description: String,
)

@Composable
internal fun RelayerProviderPickerDialog(
    currentProvider: String,
    currentCustomUrl: String,
    availableRelayers: Map<String, String>,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var selectedProvider by rememberSaveable { mutableStateOf(currentProvider) }
    var urlText by rememberSaveable(stateSaver = RelayerDraftSaver) { mutableStateOf(currentCustomUrl) }
    val isCustom = selectedProvider.equals(SettingsStore.RELAYER_CUSTOM, ignoreCase = true)
    val isValidCustomUrl = SettingsStore.isValidRelayerUrl(urlText)
    val canSave = !isCustom || isValidCustomUrl

    val coroutineScope = rememberCoroutineScope()
    var probeStates by remember { mutableStateOf<Map<String, RelayerProbeState>>(emptyMap()) }
    var isTesting by remember { mutableStateOf(false) }

    val options = mutableListOf<RelayerProviderOption>()
    options.add(
        RelayerProviderOption(
            key = SettingsStore.RELAYER_AUTOMATIC,
            title = stringResource(R.string.settings_relayer_automatic),
            description = stringResource(R.string.settings_relayer_automatic_desc),
        )
    )
    options.add(
        RelayerProviderOption(
            key = SettingsStore.RELAYER_DIRECT,
            title = stringResource(R.string.settings_relayer_direct),
            description = stringResource(R.string.settings_relayer_direct_desc),
        )
    )
    availableRelayers.forEach { (name, url) ->
        val title = name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        options.add(
            RelayerProviderOption(
                key = name,
                title = title,
                description = url,
            )
        )
    }
    options.add(
        RelayerProviderOption(
            key = SettingsStore.RELAYER_CUSTOM,
            title = stringResource(R.string.settings_relayer_custom),
            description = stringResource(R.string.settings_relayer_custom_desc),
        )
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_relayer_provider)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                Text(
                    stringResource(R.string.settings_relayer_provider_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                options.forEach { option ->
                    val isSelected = selectedProvider.equals(option.key, ignoreCase = true)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { selectedProvider = option.key },
                            )
                            .heightIn(min = 48.dp)
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                        )
                        Column(Modifier.padding(start = 8.dp).weight(1f)) {
                            Text(option.title)
                            if (option.description.isNotEmpty()) {
                                Text(
                                    option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val state = probeStates[option.key]
                            if (state != null && state !is RelayerProbeState.Idle) {
                                when (state) {
                                    is RelayerProbeState.Testing -> {
                                        Text(
                                            stringResource(R.string.loading),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    is RelayerProbeState.Success -> {
                                        Text(
                                            stringResource(R.string.server_list_tcp_ms, state.ms),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    is RelayerProbeState.Failed -> {
                                        Text(
                                            stringResource(R.string.server_list_tcp_unavailable),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                }
                TextButton(
                    onClick = {
                        if (isTesting) return@TextButton
                        isTesting = true
                        coroutineScope.launch {
                            val initial = mutableMapOf<String, RelayerProbeState>()
                            options.forEach { initial[it.key] = RelayerProbeState.Testing }
                            probeStates = initial

                            val deferreds = options.map { opt ->
                                async {
                                    val result = when (opt.key) {
                                        SettingsStore.RELAYER_DIRECT -> probeRelayerEndpoint(null)
                                        SettingsStore.RELAYER_CUSTOM -> {
                                            val custom = urlText.trim()
                                            if (custom.isNotBlank() && SettingsStore.isValidRelayerUrl(custom)) {
                                                probeRelayerEndpoint(custom)
                                            } else {
                                                RelayerProbeState.Failed
                                            }
                                        }
                                        SettingsStore.RELAYER_AUTOMATIC -> RelayerProbeState.Idle
                                        else -> {
                                            val url = availableRelayers.entries.firstOrNull { it.key.equals(opt.key, ignoreCase = true) }?.value
                                            if (!url.isNullOrBlank()) {
                                                probeRelayerEndpoint(url)
                                            } else {
                                                RelayerProbeState.Failed
                                            }
                                        }
                                    }
                                    opt.key to result
                                }
                            }
                            val results = deferreds.awaitAll().toMap().toMutableMap()
                            val candidates = results.filter { it.key != SettingsStore.RELAYER_AUTOMATIC }.values
                            val successful = candidates.filterIsInstance<RelayerProbeState.Success>()
                            if (successful.isNotEmpty()) {
                                val best = successful.minByOrNull { it.ms }
                                if (best != null) {
                                    results[SettingsStore.RELAYER_AUTOMATIC] = best
                                }
                            } else if (results.isNotEmpty()) {
                                results[SettingsStore.RELAYER_AUTOMATIC] = RelayerProbeState.Failed
                            }
                            probeStates = results
                            isTesting = false
                        }
                    },
                    enabled = !isTesting,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.settings_relayer_test_latency))
                }
                if (isCustom) {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = {
                            urlText = it.trim()
                            probeStates = probeStates - SettingsStore.RELAYER_CUSTOM
                        },
                        label = { Text(stringResource(R.string.settings_relayer_custom_url)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                        ),
                        placeholder = { Text(stringResource(R.string.settings_relayer_custom_url_placeholder)) },
                        supportingText = { Text(stringResource(R.string.settings_relayer_custom_url_help)) },
                        singleLine = true,
                        isError = urlText.isNotBlank() && !isValidCustomUrl,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedProvider, urlText) },
                enabled = canSave,
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private data class RelayedEndpointOption(
    val id: String,
    val titleRes: Int,
    val descRes: Int,
)

private val ENDPOINT_OPTIONS = listOf(
    RelayedEndpointOption(
        id = "guardian",
        titleRes = R.string.settings_relayer_ep_guardian,
        descRes = R.string.settings_relayer_ep_guardian_desc,
    ),
    RelayedEndpointOption(
        id = "fxa",
        titleRes = R.string.settings_relayer_ep_fxa,
        descRes = R.string.settings_relayer_ep_fxa_desc,
    ),
    RelayedEndpointOption(
        id = "config",
        titleRes = R.string.settings_relayer_ep_config,
        descRes = R.string.settings_relayer_ep_config_desc,
    ),
    RelayedEndpointOption(
        id = "server_list",
        titleRes = R.string.settings_relayer_ep_server_list,
        descRes = R.string.settings_relayer_ep_server_list_desc,
    ),
)

@Composable
internal fun RelayedEndpointsDialog(
    currentEndpoints: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var selected by rememberSaveable(stateSaver = EndpointSelectionSaver) {
        mutableStateOf(currentEndpoints)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_relayer_endpoints)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.settings_relayer_endpoints_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ENDPOINT_OPTIONS.forEach { option ->
                    val isChecked = option.id in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = isChecked,
                                role = Role.Checkbox,
                                onValueChange = { checked ->
                                    selected = if (checked) selected + option.id else selected - option.id
                                },
                            )
                            .heightIn(min = 48.dp)
                            .padding(vertical = 4.dp),
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = null,
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(stringResource(option.titleRes))
                            Text(
                                stringResource(option.descRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
