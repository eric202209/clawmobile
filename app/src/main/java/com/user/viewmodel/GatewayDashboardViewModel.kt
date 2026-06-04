package com.user.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.user.ClawMobileApplication
import com.user.data.GatewaySettingsResolver
import com.user.service.GatewayHealthChecker
import com.user.service.GatewayProviderStatusClient
import com.user.service.GatewayProviderStatusException
import kotlinx.coroutines.launch

class GatewayDashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ClawMobileApplication
    private val healthChecker = GatewayHealthChecker()
    private val providerStatusClient = GatewayProviderStatusClient()

    private val _state = MutableLiveData<DashboardUiState>(DashboardUiState.Idle)
    val state: LiveData<DashboardUiState> = _state

    init {
        loadCachedThenRefresh()
    }

    private fun loadCachedThenRefresh() {
        viewModelScope.launch {
            val cached = app.providerStatusDao.getAll()
            if (cached.isNotEmpty()) {
                _state.value = DashboardUiState.Ready(
                    isHealthy = true,
                    providers = cached,
                    staleCache = true
                )
            }
            refresh()
        }
    }

    fun refresh() {
        if (_state.value is DashboardUiState.Refreshing) return
        viewModelScope.launch {
            _state.value = DashboardUiState.Refreshing
            val sources = GatewaySettingsResolver.resolveProviderStatusSources(getApplication())

            if (sources.isEmpty()) {
                _state.value = DashboardUiState.Error(
                    message = "No Gateway token configured.",
                    isAuth = true
                )
                return@launch
            }

            val primarySource = sources.first()
            val isHealthy = healthChecker.check(primarySource, primarySource.token).isSuccess

            providerStatusClient.fetchAny(sources)
                .onSuccess { providers ->
                    app.providerStatusDao.upsertAll(providers)
                    _state.value = DashboardUiState.Ready(
                        isHealthy = isHealthy,
                        providers = providers
                    )
                }
                .onFailure { error ->
                    val isAuth = error is GatewayProviderStatusException.Auth
                    val cached = app.providerStatusDao.getAll()
                    _state.value = if (cached.isNotEmpty()) {
                        DashboardUiState.Ready(
                            isHealthy = false,
                            providers = cached,
                            staleCache = true
                        )
                    } else {
                        DashboardUiState.Error(
                            message = when {
                                isAuth -> "Authentication failed. Check your Gateway token."
                                error is GatewayProviderStatusException.Malformed ->
                                    "Unexpected response from Gateway."
                                else -> "Could not reach Gateway."
                            },
                            isAuth = isAuth
                        )
                    }
                }
        }
    }
}
