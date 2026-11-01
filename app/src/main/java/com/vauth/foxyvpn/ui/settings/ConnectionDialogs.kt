package com.vauth.foxyvpn.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vauth.foxyvpn.R
import com.vauth.foxyvpn.data.SettingsStore

private const val MAX_SAVED_NETWORK_DRAFT_CHARS = 4096

private val NetworkDraftSaver =
    Saver<String, String>(
        save = { it.takeIf { value -> value.length <= MAX_SAVED_NETWORK_DRAFT_CHARS } },
        restore = { it },
    )

@Composable
internal fun DnsProviderPickerDialog(
    currentProvider: SettingsStore.DnsProvider,
    currentCustomUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (SettingsStore.DnsProvider, String) -> Unit,
) {
    var selectedProvider by rememberSaveable { mutableStateOf(currentProvider) }
    var urlText by
        rememberSaveable(stateSaver = NetworkDraftSaver) { mutableStateOf(currentCustomUrl) }
    val isCustom = selectedProvider == SettingsStore.DnsProvider.CUSTOM
    val isValidCustomUrl = SettingsStore.isValidDohUrl(urlText)
    val canSave = !isCustom || isValidCustomUrl

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_dns_provider)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                Text(
                    stringResource(R.string.dialog_dns_tunnel_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SettingsStore.DnsProvider.entries.forEach { provider ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = selectedProvider == provider,
                                    role = Role.RadioButton,
                                    onClick = { selectedProvider = provider },
                                )
                                .heightIn(min = 48.dp)
                                .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = selectedProvider == provider, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(provider.label)
                            val detail =
                                when (provider) {
                                    SettingsStore.DnsProvider.DIRECT_SYSTEM ->
                                        stringResource(R.string.dialog_dns_direct_system)
                                    SettingsStore.DnsProvider.CUSTOM ->
                                        stringResource(R.string.dialog_dns_custom_doh)
                                    else -> provider.dohUrl
                                }
                            if (detail.isNotEmpty()) {
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (isCustom) {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it.trim() },
                        label = { Text(stringResource(R.string.settings_doh_url)) },
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                autoCorrectEnabled = false,
                            ),
                        placeholder = { Text(stringResource(R.string.settings_doh_placeholder)) },
                        supportingText = { Text(stringResource(R.string.settings_doh_help)) },
                        singleLine = true,
                        isError = urlText.isNotBlank() && !isValidCustomUrl,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedProvider, urlText) }, enabled = canSave) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun CustomEdgeAddressDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(stateSaver = NetworkDraftSaver) { mutableStateOf(current) }
    val trimmed = value.trim()
    val isValid = trimmed.isEmpty() || SettingsStore.isValidEdgeHost(trimmed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_custom_edge_address)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.settings_host_or_ip)) },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            autoCorrectEnabled = false,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !isValid,
                )
                Text(
                    stringResource(R.string.dialog_custom_edge_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = isValid) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun HttpVersionPickerDialog(
    current: SettingsStore.HttpVersion,
    onDismiss: () -> Unit,
    onConfirm: (SettingsStore.HttpVersion) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_transport_protocol)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                Text(
                    stringResource(R.string.dialog_http_version_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                SettingsStore.HttpVersion.entries.forEach { version ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = selected == version,
                                    role = Role.RadioButton,
                                    onClick = { selected = version },
                                )
                                .heightIn(min = 48.dp)
                                .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = selected == version, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(version.label)
                            Text(
                                version.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun MtuPickerDialog(
    current: Int,
    minimum: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var mtuText by rememberSaveable { mutableStateOf(current.toString()) }
    val parsedMtu = mtuText.toIntOrNull()
    val isValid = parsedMtu != null && parsedMtu in minimum..SettingsStore.MAX_MTU

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_tunnel_mtu)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.dialog_mtu_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = mtuText,
                    onValueChange = { mtuText = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.settings_mtu)) },
                    singleLine = true,
                    isError = mtuText.isNotBlank() && !isValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = { mtuText = SettingsStore.DEFAULT_MTU.toString() },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.settings_reset_default))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsedMtu?.let { onConfirm(it) } }, enabled = isValid) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun TlsFragmentPickerDialog(
    currentMode: SettingsStore.TlsFragmentMode,
    currentDelay: Int,
    onDismiss: () -> Unit,
    onConfirm: (SettingsStore.TlsFragmentMode, Int) -> Unit,
) {
    var selectedMode by rememberSaveable { mutableStateOf(currentMode) }
    var delayText by rememberSaveable { mutableStateOf(currentDelay.toString()) }
    val parsedDelay = delayText.toIntOrNull()
    val isValidDelay = parsedDelay != null && parsedDelay in 50..2000

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_tls_fragmentation)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                Text(
                    stringResource(R.string.dialog_fragment_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                SettingsStore.TlsFragmentMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = selectedMode == mode,
                                    role = Role.RadioButton,
                                    onClick = { selectedMode = mode },
                                )
                                .heightIn(min = 48.dp)
                                .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = selectedMode == mode, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(mode.label)
                            Text(
                                mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (
                    selectedMode == SettingsStore.TlsFragmentMode.PACKET ||
                        selectedMode == SettingsStore.TlsFragmentMode.COMBINED
                ) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = delayText,
                        onValueChange = { delayText = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.settings_fallback_delay_ms)) },
                        singleLine = true,
                        isError = delayText.isNotBlank() && !isValidDelay,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        selectedMode,
                        parsedDelay ?: SettingsStore.DEFAULT_FRAGMENT_FALLBACK_DELAY,
                    )
                },
                enabled =
                    selectedMode == SettingsStore.TlsFragmentMode.OFF ||
                        selectedMode == SettingsStore.TlsFragmentMode.RECORD ||
                        isValidDelay,
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun UtlsFingerprintPickerDialog(
    current: SettingsStore.UtlsFingerprint,
    onDismiss: () -> Unit,
    onConfirm: (SettingsStore.UtlsFingerprint) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_browser_tls_fingerprint)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                Text(
                    stringResource(R.string.dialog_utls_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                SettingsStore.UtlsFingerprint.entries.forEach { fp ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = selected == fp,
                                    role = Role.RadioButton,
                                    onClick = { selected = fp },
                                )
                                .heightIn(min = 48.dp)
                                .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = selected == fp, onClick = null)
                        Text(fp.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun ConcurrencyPickerDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val options = listOf(8, 16, 32, 64)
    var selected by rememberSaveable { mutableIntStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_multiplex_concurrency)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                Text(
                    stringResource(R.string.dialog_concurrency_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                options.forEach { count ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = selected == count,
                                    role = Role.RadioButton,
                                    onClick = { selected = count },
                                )
                                .heightIn(min = 48.dp)
                                .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = selected == count, onClick = null)
                        val desc = pluralStringResource(R.plurals.stream_count, count, count)
                        Text(desc, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun LatencyProbeDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable(stateSaver = NetworkDraftSaver) { mutableStateOf(current) }
    val valid = SettingsStore.isValidProbeUrl(url.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_latency_test_url)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.settings_latency_help))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.settings_https_url)) },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !valid,
                )
                TextButton(onClick = { url = SettingsStore.DEFAULT_LATENCY_PROBE_URL }) {
                    Text(stringResource(R.string.settings_reset))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(url.trim()) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
