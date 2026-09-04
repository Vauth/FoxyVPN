package com.vauth.foxyvpn.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vauth.foxyvpn.data.model.RuntimeAuth

class TokenStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "foxyvpn_tokens",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun saveAuth(auth: RuntimeAuth) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, auth.accessToken)
            .putString(KEY_REFRESH_TOKEN, auth.refreshToken)
            .putLong(KEY_EXPIRES_AT, auth.expiresAtEpochSeconds)
            .apply()
    }

    fun loadAuth(): RuntimeAuth? {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        return RuntimeAuth(access, prefs.getString(KEY_REFRESH_TOKEN, null), expiresAt)
    }

    fun hasValidSession(): Boolean {
        val auth = loadAuth() ?: return false
        val nowSeconds = System.currentTimeMillis() / 1000

        return auth.expiresAtEpochSeconds - nowSeconds > CLOCK_SKEW_TOLERANCE_SECONDS
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val CLOCK_SKEW_TOLERANCE_SECONDS = 30L
    }
}
