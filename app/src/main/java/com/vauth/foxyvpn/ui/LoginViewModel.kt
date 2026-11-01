package com.vauth.foxyvpn.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vauth.foxyvpn.data.FxaAuthRepository
import com.vauth.foxyvpn.data.isUnconfirmedSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class LoginUiState(
    val browserStartUrl: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val signedIn: Boolean = false,
)

class LoginViewModel(private val repository: FxaAuthRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    val browserStartUrl: String? get() = _uiState.value.browserStartUrl
    val isLoading: Boolean get() = _uiState.value.isLoading
    val errorMessage: String? get() = _uiState.value.errorMessage
    val signedIn: Boolean get() = _uiState.value.signedIn

    private var operation: Job? = null
    private var generation = 0

    fun startBrowserLogin() {
        if (_uiState.value.isLoading || _uiState.value.signedIn) return
        _uiState.update { it.copy(errorMessage = null, browserStartUrl = repository.getBrowserLoginUrl()) }
    }

    fun cancelVerification() {
        generation++
        operation?.cancel()
        operation = null
        _uiState.value = LoginUiState()
    }

    fun completeBrowserLoginWithTokens(
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long,
    ) {
        if (operation?.isActive == true || _uiState.value.isLoading || _uiState.value.signedIn || _uiState.value.browserStartUrl == null) return
        val requestGeneration = ++generation
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        operation =
            viewModelScope.launch {
                try {
                    val result =
                        withTimeout(120_000L) {
                            repository.completeBrowserLoginWithTokens(
                                accessToken,
                                refreshToken,
                                expiresInSeconds,
                            )
                        }
                    if (generation != requestGeneration) return@launch
                    result
                        .onSuccess {
                            _uiState.update { it.copy(browserStartUrl = null, signedIn = true) }
                        }
                        .onFailure {
                            _uiState.update { s -> s.copy(errorMessage = it.message ?: "Browser sign-in failed. Please try again.") }
                        }
                } catch (_: TimeoutCancellationException) {
                    if (generation == requestGeneration)
                        _uiState.update { it.copy(errorMessage = "Sign-in timed out. Try again.") }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (generation == requestGeneration)
                        _uiState.update { it.copy(errorMessage = "Sign-in failed. Try again.") }
                } finally {
                    if (generation == requestGeneration)
                        _uiState.update { it.copy(isLoading = false) }
                }
            }
    }

    fun completeBrowserLogin(sessionToken: String) {
        if (operation?.isActive == true || _uiState.value.isLoading || _uiState.value.signedIn || _uiState.value.browserStartUrl == null) return
        val requestGeneration = ++generation
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        operation =
            viewModelScope.launch {
                try {
                    val result =
                        withTimeout(120_000L) { repository.completeBrowserLogin(sessionToken) }
                    if (generation != requestGeneration) return@launch
                    result
                        .onSuccess {
                            _uiState.update { it.copy(browserStartUrl = null, signedIn = true) }
                        }
                        .onFailure {
                            if (!it.isUnconfirmedSession()) {
                                _uiState.update { s ->
                                    s.copy(errorMessage = it.message ?: "Browser sign-in failed. Please try again.")
                                }
                            }
                        }
                } catch (_: TimeoutCancellationException) {
                    if (generation == requestGeneration)
                        _uiState.update { it.copy(errorMessage = "Sign-in timed out. Try again.") }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (generation == requestGeneration)
                        _uiState.update { it.copy(errorMessage = "Sign-in failed. Try again.") }
                } finally {
                    if (generation == requestGeneration)
                        _uiState.update { it.copy(isLoading = false) }
                }
            }
    }

    companion object {
        fun factory(repository: FxaAuthRepository) = viewModelFactory {
            initializer { LoginViewModel(repository) }
        }
    }
}
