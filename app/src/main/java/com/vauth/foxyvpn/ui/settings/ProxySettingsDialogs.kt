package com.vauth.foxyvpn.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vauth.foxyvpn.R
import com.vauth.foxyvpn.data.LocalProxyOptions
import com.vauth.foxyvpn.data.SettingsStore
import com.vauth.foxyvpn.data.UpstreamProxyOptions
import com.vauth.foxyvpn.ui.ProxyEditor
import com.vauth.foxyvpn.ui.ProxySettingsUiState

@Composable
internal fun ProxySettingsDialog(
    state: ProxySettingsUiState,
    onDismiss: () -> Unit,
    onRetry: (ProxyEditor) -> Unit,
    onSaveLocal: (LocalProxyOptions) -> Unit,
    onSaveUpstream: (UpstreamProxyOptions) -> Unit,
) {
    val editor = state.editor ?: return
    val errorMessage = state.errorRes?.let { stringResource(it) } ?: state.error
    when {
        editor == ProxyEditor.LOCAL && state.local != null ->
            LocalProxyDialog(state.local, state.busy, errorMessage, onDismiss, onSaveLocal)
        editor != ProxyEditor.LOCAL && state.upstream != null ->
            UpstreamProxyDialog(
                checkNotNull(state.editor),
                state.upstream,
                state.busy,
                errorMessage,
                onDismiss,
                onSaveUpstream,
            )
        else ->
            AlertDialog(
                onDismissRequest = { if (!state.busy) onDismiss() },
                title = {
                    Text(
                        stringResource(
                            if (editor == ProxyEditor.LOCAL) R.string.local_proxy
                            else R.string.upstream_proxy
                        )
                    )
                },
                text = {
                    if (state.busy) CircularProgressIndicator()
                    else Text(errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
                },
                confirmButton = {
                    TextButton(enabled = !state.busy, onClick = { onRetry(editor) }) {
                        Text(stringResource(R.string.retry))
                    }
                },
                dismissButton = {
                    TextButton(enabled = !state.busy, onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
    }
}

@Composable
private fun LocalProxyDialog(
    initial: LocalProxyOptions,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (LocalProxyOptions) -> Unit,
) {
    var port by remember(initial) { mutableStateOf(initial.port.toString()) }

    var authentication by remember(initial) { mutableStateOf(initial.authentication) }
    var username by remember(initial) { mutableStateOf(initial.username) }
    var password by remember(initial) { mutableStateOf(initial.password) }
    val validPort = SettingsStore.isValidLocalPort(port.toIntOrNull() ?: 0)
    val validCredentials =
        !authentication || SettingsStore.isValidLocalProxyCredentials(username, password)
    ProxyEditorDialog(
        stringResource(R.string.local_proxy),
        busy,
        error,
        validPort && validCredentials,
        onDismiss,
        { onSave(LocalProxyOptions(port.toInt(), authentication, username, password)) },
    ) {
        Text(stringResource(R.string.local_address_value, SettingsStore.DEFAULT_SOCKS_BIND_ADDRESS))
        PortField(port, { port = it }, stringResource(R.string.mixed_port), !busy, !validPort)
        Text(
            stringResource(R.string.local_port_help, SettingsStore.CONTROLLER_PORT),
            style = MaterialTheme.typography.bodySmall,
        )
        ProxyToggle(stringResource(R.string.proxy_authentication), authentication, !busy) {
            authentication = it
        }
        if (authentication) {
            Text(
                stringResource(R.string.local_auth_help),
                style = MaterialTheme.typography.bodySmall,
            )
            CredentialFields(
                username,
                { username = it },
                password,
                { password = it },
                !busy,
                !validCredentials,
            )
        }
    }
}

@Composable
private fun UpstreamProxyDialog(
    editor: ProxyEditor,
    initial: UpstreamProxyOptions,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (UpstreamProxyOptions) -> Unit,
) {
    var type by remember(initial) { mutableStateOf(initial.type) }
    var host by remember(initial) { mutableStateOf(initial.host) }
    var port by remember(initial) { mutableStateOf(initial.port.toString()) }
    var username by remember(initial) { mutableStateOf(initial.username) }
    var password by remember(initial) { mutableStateOf(initial.password) }
    val validHost = SettingsStore.isValidProxyHost(host.trim().removeSurrounding("[", "]"))
    val validPort = port.toIntOrNull() in 1..65_535
    val validCredentials = SettingsStore.isValidUpstreamProxyCredentials(type, username, password)
    val valid =
        when (editor) {
            ProxyEditor.UPSTREAM_TYPE -> true
            ProxyEditor.UPSTREAM_ADDRESS -> validHost && validPort
            else -> validCredentials
        }
    val title =
        when (editor) {
            ProxyEditor.UPSTREAM_TYPE -> R.string.proxy_type
            ProxyEditor.UPSTREAM_ADDRESS -> R.string.proxy_address
            else -> R.string.proxy_credentials
        }
    ProxyEditorDialog(
        stringResource(title),
        busy,
        error,
        valid,
        onDismiss,
        {
            onSave(
                UpstreamProxyOptions(
                    initial.enabled,
                    type,
                    host,
                    port.toIntOrNull() ?: initial.port,
                    username,
                    password,
                )
            )
        },
    ) {
        when (editor) {
            ProxyEditor.UPSTREAM_TYPE ->
                Column(Modifier.selectableGroup()) {
                    SettingsStore.UpstreamProxyType.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = type == option,
                                    enabled = !busy,
                                    role = Role.RadioButton,
                                    onClick = { type = option },
                                )
                                .heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = type == option, onClick = null, enabled = !busy)
                            Text(option.name, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            ProxyEditor.UPSTREAM_ADDRESS -> {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.proxy_host)) },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            autoCorrectEnabled = false,
                        ),
                    singleLine = true,
                    enabled = !busy,
                    isError = !validHost,
                    modifier = Modifier.fillMaxWidth(),
                )
                PortField(
                    port,
                    { port = it },
                    stringResource(R.string.proxy_port),
                    !busy,
                    !validPort,
                )
                Text(
                    stringResource(
                        if (initial.type == SettingsStore.UpstreamProxyType.HTTP)
                            R.string.upstream_http_help
                        else R.string.upstream_socks_help
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> {
                Text(
                    stringResource(R.string.upstream_auth_help),
                    style = MaterialTheme.typography.bodySmall,
                )
                CredentialFields(
                    username,
                    { username = it },
                    password,
                    { password = it },
                    !busy,
                    !validCredentials,
                )
            }
        }
    }
}

@Composable
private fun ProxyEditorDialog(
    title: String,
    busy: Boolean,
    error: String?,
    valid: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = onSave) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun PortField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    invalid: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CredentialFields(
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    enabled: Boolean,
    invalid: Boolean,
) {
    OutlinedTextField(
        value = username,
        onValueChange = onUsername,
        label = { Text(stringResource(R.string.username)) },
        singleLine = true,
        enabled = enabled,
        isError = invalid,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
    )
    OutlinedTextField(
        value = password,
        onValueChange = onPassword,
        label = { Text(stringResource(R.string.password)) },
        singleLine = true,
        enabled = enabled,
        isError = invalid,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

@Composable
internal fun ProxyToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
