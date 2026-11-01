package com.vauth.foxyvpn

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.vauth.foxyvpn.ui.navigation.FoxyNavGraph
import com.vauth.foxyvpn.ui.theme.FoxyVpnTheme
import com.vauth.foxyvpn.ui.theme.rememberThemeController
import com.vauth.foxyvpn.update.UpdatePrompt
import com.vauth.foxyvpn.vpn.FoxyVpnService

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                requestNotificationPermissionIfNeeded()
                FoxyVpnService.start(this)
            } else {

                Toast.makeText(
                        this,
                        getString(R.string.vpn_permission_denied_help),
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun requestConnect() {
        if ((application as FoxyVpnApp).settingsStore.proxyOnlyMode) {
            requestNotificationPermissionIfNeeded()
            FoxyVpnService.start(this)
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            requestNotificationPermissionIfNeeded()
            FoxyVpnService.start(this)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val alreadyGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!alreadyGranted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        (application as FoxyVpnApp).appUpdater.check(automatic = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as FoxyVpnApp
        setContent {
            val themeController = rememberThemeController(app.settingsStore)
            FoxyVpnTheme(themeMode = themeController.mode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    UpdatePrompt(app.appUpdater)
                    FoxyNavGraph(
                        navController = navController,
                        container = app,
                        themeController = themeController,
                        onRequestConnect = { requestConnect() },
                        onDisconnect = { FoxyVpnService.stop(this) },
                    )
                }
            }
        }
    }
}
