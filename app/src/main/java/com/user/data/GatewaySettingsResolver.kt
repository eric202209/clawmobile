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
}
