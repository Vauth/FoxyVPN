package com.vauth.foxyvpn.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vauth.foxyvpn.R
import com.vauth.foxyvpn.data.LocalProxyOptions
import com.vauth.foxyvpn.data.ProxySettingsRepository
import com.vauth.foxyvpn.data.ProxySummary
import com.vauth.foxyvpn.data.SettingsStore
import com.vauth.foxyvpn.data.UpstreamProxyOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ProxyEditor {
    LOCAL,
    UPSTREAM_TYPE,
    UPSTREAM_ADDRESS,
    UPSTREAM_CREDENTIALS,
}

data class ProxySettingsUiState(
    val summary: ProxySummary? = null,
    val editor: ProxyEditor? = null,
    val local: LocalProxyOptions? = null,
    val upstream: UpstreamProxyOptions? = null,
    val upstreamAddressReviewed: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val errorRes: Int? = null,
)

class ProxySettingsViewModel(private val repository: ProxySettingsRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ProxySettingsUiState())
    val state = mutableState.asStateFlow()

    init {
        reload()
    }

    fun reload() = perform { mutableState.value = ProxySettingsUiState(repository.summary()) }

    fun open(editor: ProxyEditor) {
        if (mutableState.value.busy || mutableState.value.summary == null) return
        mutableState.value =
            mutableState.value.copy(
                editor = editor,
                local = null,
                upstream = null,
                upstreamAddressReviewed = false,
                error = null,
                errorRes = null,
            )
        perform {
            val localOpt = if (editor == ProxyEditor.LOCAL) repository.local() else null
            val upstreamOpt = if (editor != ProxyEditor.LOCAL) repository.upstream() else null
            mutableState.value =
                mutableState.value.copy(
                    local = localOpt,
                    upstream = upstreamOpt,
                )
        }
    }

    fun close() {
        if (!mutableState.value.busy)
            mutableState.value =
                mutableState.value.copy(
                    editor = null,
                    local = null,
                    upstream = null,
                    upstreamAddressReviewed = false,
                    error = null,
                    errorRes = null,
                )
    }

    fun setUpstreamEnabled(enabled: Boolean) = perform {
        repository.setUpstreamEnabled(enabled)
        val refreshed = repository.summary()
        mutableState.value = mutableState.value.copy(summary = refreshed)
    }

    fun save(options: LocalProxyOptions) = perform {
        repository.save(options)
        val refreshed = repository.summary()
        mutableState.value =
            mutableState.value.copy(
                summary = refreshed,
                editor = null,
                local = null,
                error = null,
                errorRes = null,
            )
    }

    fun save(options: UpstreamProxyOptions) = perform {
        if (mutableState.value.editor == ProxyEditor.UPSTREAM_ADDRESS) {
            mutableState.value = mutableState.value.copy(upstreamAddressReviewed = true)
        }
        if (
            options.enabled &&
                mutableState.value.summary?.upstreamReviewRequired == true &&
                !mutableState.value.upstreamAddressReviewed
        ) {
            mutableState.value =
                mutableState.value.copy(
                    editor = ProxyEditor.UPSTREAM_ADDRESS,
                    upstream = options,
                    error = null,
                    errorRes = R.string.proxy_tls_review_error,
                )
        } else if (
            options.enabled &&
                !SettingsStore.isValidUpstreamProxyCredentials(
                    options.type,
                    options.username,
                    options.password,
                )
        ) {
            mutableState.value =
                mutableState.value.copy(
                    editor = ProxyEditor.UPSTREAM_CREDENTIALS,
                    upstream = options,
                    error = null,
                    errorRes = R.string.proxy_credentials_invalid_error,
                )
        } else {
            repository.save(options)
            val refreshed = repository.summary()
            mutableState.value =
                mutableState.value.copy(
                    summary = refreshed,
                    editor = null,
                    upstream = null,
                    upstreamAddressReviewed = false,
                    error = null,
                    errorRes = null,
                )
        }
    }

    private fun perform(action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, error = null, errorRes = null)
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value =
                    mutableState.value.copy(
                        error = error.message ?: "Could not update proxy settings",
                        errorRes = null,
                    )
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    companion object {
        fun factory(repository: ProxySettingsRepository) = viewModelFactory {
            initializer { ProxySettingsViewModel(repository) }
        }
    }
}
