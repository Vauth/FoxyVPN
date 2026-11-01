package com.vauth.foxyvpn.data

import android.content.Context
import com.vauth.foxyvpn.data.model.RuntimeAuth

private const val TAG = "TokenStore"

class TokenStore(context: Context) {

    private val mutableStorageError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val storageError: kotlinx.coroutines.flow.StateFlow<String?> = mutableStorageError
    private val appContext = context.applicationContext
    private val prefs by lazy { SecurePreferences(appContext, "foxyvpn_tokens", ::storageFailure) }

    private fun storageFailure(error: Exception): SecureStorageException {
        val failure = error as? SecureStorageException ?: SecureStorageException(error)
        mutableStorageError.value = failure.message
        return failure
    }

    val sessionEpoch: Long
        get() = epoch

    private val invalidationListener =
        java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
    var onSessionInvalidated: (() -> Unit)?
        get() = invalidationListener.get()
        set(value) {
            invalidationListener.set(value)
        }

    fun clearSessionInvalidationListener(expected: () -> Unit) {
        invalidationListener.compareAndSet(expected, null)
    }

    fun <T> withCurrentSession(expectedEpoch: Long, block: () -> T): T =
        synchronized(sessionLock) {
            check(epoch == expectedEpoch && hasStoredSession()) { "Session changed" }
            block()
        }

    fun saveAuthIfCurrent(auth: RuntimeAuth, expectedEpoch: Long) =
        synchronized(sessionLock) {
            check(epoch == expectedEpoch) {
                "Session changed while credentials were being refreshed"
            }
            saveAuth(auth)
        }

    private fun saveAuth(auth: RuntimeAuth) =
        synchronized(sessionLock) {
            prefs
                .edit()
                .putString(KEY_ACCESS_TOKEN, auth.accessToken)
                .putString(KEY_REFRESH_TOKEN, auth.refreshToken)
                .putLong(KEY_EXPIRES_AT, auth.expiresAtEpochSeconds)
                .commit()
        }

    fun loadAuth(): RuntimeAuth? =
        synchronized(sessionLock) {
            runCatching {
                    val access = prefs.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }
                    mutableStorageError.value = null
                    if (access == null) return@runCatching null
                    val refresh =
                        prefs.getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() }
                    val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
                    mutableStorageError.value = null
                    RuntimeAuth(access, refresh, expiresAt)
                }
                .onFailure {
                    storageFailure(
                        it as? Exception ?: IllegalStateException("Could not read credentials", it)
                    )
                    AppLogger.w(TAG, "could not read the stored session", it)
                }
                .getOrNull()
        }

    fun hasValidAccessToken(): Boolean {
        val auth = loadAuth() ?: return false
        if (auth.expiresAtEpochSeconds <= 0L) return false
        val nowSeconds = System.currentTimeMillis() / 1000
        return auth.expiresAtEpochSeconds - nowSeconds > CLOCK_SKEW_TOLERANCE_SECONDS
    }

    fun hasStoredSession(): Boolean = loadAuth() != null

    fun clear() {
        clearIfCurrent(null)
    }

    fun clearIfCurrent(expectedEpoch: Long?): Boolean {
        val listener =
            synchronized(sessionLock) {
                if (expectedEpoch != null && epoch != expectedEpoch) return false
                prefs.edit().clear().commit()
                epoch++
                onSessionInvalidated
            }
        listener?.invoke()
        return true
    }

    fun completeLoginIfCurrent(auth: RuntimeAuth, expectedEpoch: Long) {
        synchronized(sessionLock) {
            check(epoch == expectedEpoch) { "Sign-in was superseded" }
            prefs
                .edit()
                .putString(KEY_ACCESS_TOKEN, auth.accessToken)
                .putString(KEY_REFRESH_TOKEN, auth.refreshToken)
                .putLong(KEY_EXPIRES_AT, auth.expiresAtEpochSeconds)
                .commit()
            epoch++
        }
    }

    companion object {
        private val sessionLock = Any()
        @Volatile private var epoch = 0L
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"

        private const val CLOCK_SKEW_TOLERANCE_SECONDS = 60L
    }
}
