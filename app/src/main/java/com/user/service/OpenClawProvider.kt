package com.user.service

import com.user.data.AppPreferences
import com.user.data.ChatMessage
import com.user.data.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class OpenClawProvider(
    private val getBaseUrl: suspend () -> String,
    private val getToken: () -> String
) : AiProvider {

    constructor(appPreferences: AppPreferences, prefs: PrefsManager) : this(
        getBaseUrl = { appPreferences.getBaseUrl() },
        getToken = { prefs.gatewayToken }
    )

    override val id = "openclaw"
    override val displayName = "OpenClaw Gateway"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun healthCheck(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = getBaseUrl()
            val settings = AppPreferences.parseUrl(url)
            GatewayHealthChecker().check(settings, getToken()).getOrThrow()
        }
    }

    override suspend fun sendMessage(message: String, history: List<ChatMessage>): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val api = buildApi(getBaseUrl())
                val response = api.sendMessage(
                    "Bearer ${getToken()}",
                    SendMessageRequest(
                        sessionId = "mobile-${System.currentTimeMillis()}",
                        message = message,
                        timestamp = System.currentTimeMillis()
                    )
                )
                if (!response.success || response.response == null) {
                    error(response.error ?: "Empty response from server")
                }
                response.response
            }
        }

    private fun buildApi(baseUrl: String): OpenClawApi {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenClawApi::class.java)
    }
}
