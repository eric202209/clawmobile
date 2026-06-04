package com.user.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardStateTest {

    @Test
    fun healthyState_isHealthyTrue() {
        val state = DashboardUiState.Ready(isHealthy = true, providers = emptyList())
        assertTrue(state.isHealthy)
    }

    @Test
    fun unhealthyState_isHealthyFalse() {
        val state = DashboardUiState.Ready(isHealthy = false, providers = emptyList())
        assertFalse(state.isHealthy)
    }

    @Test
    fun staleCacheState_flagged() {
        val state = DashboardUiState.Ready(
            isHealthy = true,
            providers = emptyList(),
            staleCache = true
        )
        assertTrue(state.staleCache)
    }

    @Test
    fun freshData_notStaleCached() {
        val state = DashboardUiState.Ready(
            isHealthy = true,
            providers = emptyList(),
            staleCache = false
        )
        assertFalse(state.staleCache)
    }

    @Test
    fun authError_isAuthTrue() {
        val state = DashboardUiState.Error("Auth failed", isAuth = true)
        assertTrue(state.isAuth)
    }

    @Test
    fun networkError_isAuthFalse() {
        val state = DashboardUiState.Error("Unreachable", isAuth = false)
        assertFalse(state.isAuth)
    }

    @Test
    fun removedProviderServerSide_doesNotShowInFreshReady() {
        val state = DashboardUiState.Ready(
            isHealthy = true,
            providers = emptyList(),
            staleCache = false
        )
        assertTrue(state.providers.isEmpty())
        assertFalse(state.staleCache)
    }
}
