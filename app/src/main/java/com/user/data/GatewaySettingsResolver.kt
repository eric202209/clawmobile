package com.user.data

import android.content.Context

object GatewaySettingsResolver {
    suspend fun resolve(context: Context): BackendSettings {
        val dataStoreSettings = AppPreferences(context).getBackendSettings()
        if (dataStoreSettings.token.isNotBlank()) return dataStoreSettings

        val prefs = PrefsManager(context)
        val fallbackToken = prefs.gatewayToken
        if (fallbackToken.isBlank()) return dataStoreSettings

        val fallbackSettings = AppPreferences.parseUrl(prefs.serverUrl)
        return fallbackSettings.copy(token = fallbackToken)
    }

    suspend fun resolveProviderStatusSources(context: Context): List<BackendSettings> {
        val prefs = PrefsManager(context)
        val sources = mutableListOf<BackendSettings>()

        resolve(context).takeIf { it.token.isNotBlank() }?.let { sources += it }

        val orchestratorToken = prefs.orchestratorApiKey.ifBlank { prefs.gatewayToken }
        if (prefs.orchestratorServerUrl.isNotBlank() && orchestratorToken.isNotBlank()) {
            sources += AppPreferences.parseUrl(prefs.orchestratorServerUrl)
                .copy(token = orchestratorToken)
        }

        return sources.distinctBy { "${it.baseUrl}|${it.token}" }
    }
}
