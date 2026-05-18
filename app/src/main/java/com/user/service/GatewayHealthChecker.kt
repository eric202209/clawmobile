package com.user.service

import com.user.data.BackendSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class GatewayHealthChecker(
    private val timeoutSeconds: Long = 10
) {
    suspend fun check(settings: BackendSettings, token: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("${settings.baseUrl.trimEnd('/')}/health")
                    .header("Authorization", "Bearer $token")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Gateway returned HTTP ${response.code}")
                    }
                }
            }
        }
}
