package com.user.viewmodel

import com.user.data.GatewayProviderStatus

sealed class DashboardUiState {
    object Idle : DashboardUiState()
    object Refreshing : DashboardUiState()
    data class Ready(
        val isHealthy: Boolean,
        val providers: List<GatewayProviderStatus>,
        val staleCache: Boolean = false
    ) : DashboardUiState()
    data class Error(val message: String, val isAuth: Boolean = false) : DashboardUiState()
}
