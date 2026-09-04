package com.vauth.foxyvpn.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vauth.foxyvpn.data.TokenStore
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    tokenStore: TokenStore,
    onSignedIn: () -> Unit,
    onNeedsLogin: () -> Unit,
) {
    LaunchedEffect(Unit) {
        delay(500)
        if (tokenStore.hasValidSession()) onSignedIn() else onNeedsLogin()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                modifier = Modifier.padding(bottom = 16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("FoxyVPN", fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
