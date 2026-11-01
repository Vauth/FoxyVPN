package com.vauth.foxyvpn.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vauth.foxyvpn.data.SettingsStore
import com.vauth.foxyvpn.data.ThemeMode

class ThemeController(
    private val settingsStore: SettingsStore,
    private val onChanged: () -> Unit = {},
) {

    var mode: ThemeMode by mutableStateOf(settingsStore.themeMode)
        private set

    fun resolveDark(systemInDarkTheme: Boolean): Boolean =
        when (mode) {
            ThemeMode.SYSTEM -> systemInDarkTheme
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }

    fun set(next: ThemeMode) {
        if (next == mode) return
        mode = next
        settingsStore.themeMode = next
        onChanged()
    }

    fun toggle(systemInDarkTheme: Boolean) {
        set(if (resolveDark(systemInDarkTheme)) ThemeMode.LIGHT else ThemeMode.DARK)
    }
}

@Composable
fun rememberThemeController(settingsStore: SettingsStore): ThemeController =
    androidx.compose.ui.platform.LocalContext.current.applicationContext.let { context ->
        remember(settingsStore, context) {
            ThemeController(settingsStore) {
                com.vauth.foxyvpn.widget.QuickConnectWidgetProvider.updateAll(
                    context,
                    com.vauth.foxyvpn.vpn.FoxyVpnService.state.value,
                )
            }
        }
    }
