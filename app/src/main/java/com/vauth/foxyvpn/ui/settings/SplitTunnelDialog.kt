package com.vauth.foxyvpn.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vauth.foxyvpn.R
import com.vauth.foxyvpn.data.AppLogger
import com.vauth.foxyvpn.data.InstalledApp
import com.vauth.foxyvpn.data.SettingsStore
import com.vauth.foxyvpn.data.filterInstalledApps
import com.vauth.foxyvpn.data.loadInstalledApps

private sealed interface AppListState {
    data object Loading : AppListState

    data class Ready(val apps: List<InstalledApp>) : AppListState

    data object Failed : AppListState
}

private val PackageSelectionSaver =
    listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

@Composable
internal fun SplitTunnelDialog(
    currentMode: SettingsStore.SplitTunnelMode,
    currentExcluded: Set<String>,
    currentIncluded: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (SettingsStore.SplitTunnelMode, Set<String>, Set<String>) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    var mode by rememberSaveable { mutableStateOf(currentMode) }
    var excluded by
        rememberSaveable(stateSaver = PackageSelectionSaver) { mutableStateOf(currentExcluded) }
    var included by
        rememberSaveable(stateSaver = PackageSelectionSaver) { mutableStateOf(currentIncluded) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    val selected = if (mode == SettingsStore.SplitTunnelMode.INCLUDE) included else excluded
    fun toggle(pkg: String) {
        val next = if (pkg in selected) selected - pkg else selected + pkg
        if (mode == SettingsStore.SplitTunnelMode.INCLUDE) included = next else excluded = next
    }
    val appList by
        produceState<AppListState>(AppListState.Loading, context, loadAttempt) {
            value = AppListState.Loading
            value =
                try {
                    AppListState.Ready(loadInstalledApps(context))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLogger.w("SplitTunnelDialog", "Could not load installed apps", error)
                    AppListState.Failed
                }
        }
    val installedApps = (appList as? AppListState.Ready)?.apps.orEmpty()
    val filteredApps =
        remember(installedApps, searchQuery, showSystemApps, selected) {
            filterInstalledApps(installedApps, searchQuery, showSystemApps, selected)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_split_tunneling)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item(key = "split_header") {
                    Column(Modifier.selectableGroup()) {
                        SettingsStore.SplitTunnelMode.entries.forEach { option ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .selectable(
                                            selected = mode == option,
                                            role = Role.RadioButton,
                                            onClick = { mode = option },
                                        )
                                        .heightIn(min = 48.dp),
                            ) {
                                RadioButton(selected = mode == option, onClick = null)
                                Text(
                                    stringResource(
                                        if (option == SettingsStore.SplitTunnelMode.INCLUDE)
                                            R.string.settings_include_selected_apps
                                        else R.string.settings_exclude_selected_apps
                                    )
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(
                            if (mode == SettingsStore.SplitTunnelMode.INCLUDE)
                                R.string.settings_include_apps_help
                            else R.string.settings_exclude_apps_help
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pluralStringResource(
                                R.plurals.settings_selected_apps_count,
                                selected.size,
                                selected.size,
                            ),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        TextButton(
                            onClick = {
                                if (mode == SettingsStore.SplitTunnelMode.INCLUDE)
                                    included = emptySet()
                                else excluded = emptySet()
                            },
                            enabled = selected.isNotEmpty(),
                        ) {
                            Text(stringResource(R.string.settings_clear_selection))
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(
                                value = showSystemApps,
                                role = Role.Switch,
                                onValueChange = { showSystemApps = it },
                            )
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.settings_show_system_apps),
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        Switch(checked = showSystemApps, onCheckedChange = null)
                    }
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text(stringResource(R.string.settings_search_apps)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                item(key = "split_status") {
                    when {
                        appList == AppListState.Loading ->
                            CircularProgressIndicator(Modifier.padding(24.dp))
                        appList == AppListState.Failed -> {
                            Text(
                                stringResource(R.string.settings_apps_load_failed),
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = { loadAttempt++ }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                        installedApps.isEmpty() -> Text(stringResource(R.string.settings_no_apps))
                        filteredApps.isEmpty() ->
                            Text(stringResource(R.string.settings_no_app_matches))
                    }
                }
                if (appList is AppListState.Ready) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .toggleable(
                                        value = app.packageName in selected,
                                        role = Role.Checkbox,
                                        onValueChange = { toggle(app.packageName) },
                                    )
                                    .heightIn(min = 48.dp)
                                    .padding(vertical = 2.dp),
                        ) {
                            Checkbox(checked = app.packageName in selected, onCheckedChange = null)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(app.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    app.packageName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item(key = "split_footer") {
                    if (mode == SettingsStore.SplitTunnelMode.INCLUDE && included.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_include_app_required),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        stringResource(R.string.settings_lockdown_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled =
                    appList is AppListState.Ready &&
                        (mode != SettingsStore.SplitTunnelMode.INCLUDE || included.isNotEmpty()),
                onClick = { onConfirm(mode, excluded, included) },
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
