package com.vauth.foxyvpn.data

import android.content.Context
import android.content.SharedPreferences

internal object LegacyCredentialReader {
    @Suppress("DEPRECATION")
    fun open(context: Context, name: String): SharedPreferences {
        CredentialKeys.get(
            androidx.security.crypto.MasterKey.DEFAULT_MASTER_KEY_ALIAS,
            create = false,
        )
        val masterKey =
            androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
        return androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
