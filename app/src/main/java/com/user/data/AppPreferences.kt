package com.user.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.user.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.net.URI

val Context.dataStore by preferencesDataStore(name = "settings")

data class BackendSettings(
    val host: String = "localhost",
    val port: Int = 8080,
    val token: String = "",
    val useHttps: Boolean = false
) {
    val baseUrl: String
        get() = "${if (useHttps) "https" else "http"}://$host:$port"
}

class AppPreferences(private val context: Context) {
    private object PrefKeys {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val TOKEN = stringPreferencesKey("token")
        val USE_HTTPS = booleanPreferencesKey("use_https")
    }

    val backendSettings: Flow<BackendSettings> = context.dataStore.data.map { prefs ->
        BackendSettings(
            host = prefs[PrefKeys.HOST] ?: defaultSettings.host,
            port = prefs[PrefKeys.PORT] ?: defaultSettings.port,
            token = prefs[PrefKeys.TOKEN] ?: "",
            useHttps = prefs[PrefKeys.USE_HTTPS] ?: defaultSettings.useHttps
        )
    }

    suspend fun getBackendSettings(): BackendSettings = backendSettings.first()

    suspend fun getBaseUrl(): String = getBackendSettings().baseUrl

    suspend fun saveBackendSettings(settings: BackendSettings) {
        context.dataStore.edit { prefs ->
            prefs[PrefKeys.HOST] = settings.host
            prefs[PrefKeys.PORT] = settings.port
            prefs[PrefKeys.TOKEN] = settings.token
            prefs[PrefKeys.USE_HTTPS] = settings.useHttps
        }
    }

    companion object {
        val defaultSettings: BackendSettings by lazy {
            parseUrl(BuildConfig.DEFAULT_SERVER_URL.ifBlank { "http://localhost:8080" })
        }

        fun parseUrl(rawUrl: String): BackendSettings {
            val normalized = rawUrl.trim().ifBlank { "http://localhost:8080" }
            val withScheme = if ("://" in normalized) normalized else "http://$normalized"
            val uri = URI(withScheme)
            val useHttps = uri.scheme.equals("https", ignoreCase = true)
            val host = uri.host ?: normalized.substringBefore(":").substringBefore("/")
            val port = when {
                uri.port > 0 -> uri.port
                useHttps -> 443
                else -> 80
            }
            return BackendSettings(host = host, port = port, useHttps = useHttps)
        }
    }
}
