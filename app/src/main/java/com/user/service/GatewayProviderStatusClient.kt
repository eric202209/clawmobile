package com.user.service

import com.user.data.BackendSettings
import com.user.data.GatewayProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit

class GatewayProviderStatusClient(
    private val timeoutSeconds: Long = GatewayNetworkConfig.HEALTH_TIMEOUT_SECONDS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun fetch(settings: BackendSettings, token: String): Result<List<GatewayProviderStatus>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .build()
                val baseUrl = settings.baseUrl.trimEnd('/')
                val urls = listOf(
                    "$baseUrl/providers/status",
                    "$baseUrl/api/v1/mobile/providers/status"
                )

                var lastFailure: Throwable? = null
                var lastHttpFailure: IOException? = null
                for ((index, url) in urls.withIndex()) {
                    val request = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .build()

                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        when {
                            response.code == 401 || response.code == 403 ->
                                throw GatewayProviderStatusException.Auth("Gateway auth failed (${response.code})")
                            response.code == 404 -> {
                                lastHttpFailure = IOException(
                                    "Provider status endpoint returned HTTP 404 at $url"
                                )
                            }
                            !response.isSuccessful -> {
                                lastHttpFailure = IOException(
                                    "Provider status endpoint returned HTTP ${response.code} at $url"
                                )
                                if (index == urls.lastIndex) throw lastHttpFailure
                                    ?: IOException("Provider status endpoint failed at $url")
                            }
                            else -> {
                                try {
                                    return@runCatching parse(body, nowMillis())
                                } catch (e: GatewayProviderStatusException.Malformed) {
                                    if (index == urls.lastIndex) throw e
                                    lastFailure = e
                                }
                            }
                        }
                    }
                }
                throw lastFailure ?: lastHttpFailure
                    ?: IOException("Gateway provider status endpoint was not found")
            }
        }

    fun parse(body: String, fetchedAtMillis: Long = nowMillis()): List<GatewayProviderStatus> {
        if (body.isBlank()) throw GatewayProviderStatusException.Malformed("Empty provider status response")

        val value = try {
            JSONTokener(body).nextValue()
        } catch (e: JSONException) {
            throw GatewayProviderStatusException.Malformed("Malformed provider status response", e)
        }

        val providers = when (value) {
            is JSONArray -> value
            is JSONObject -> value.providerArrayOrNull()
            else -> null
        } ?: throw GatewayProviderStatusException.Malformed("Provider status response did not include providers")

        val rows = mutableListOf<GatewayProviderStatus>()
        for (index in 0 until providers.length()) {
            val provider = providers.optJSONObject(index)
                ?: throw GatewayProviderStatusException.Malformed("Provider at index $index was not an object")
            rows.add(provider.toGatewayProviderStatus(index, fetchedAtMillis))
        }
        return rows
    }

    private fun JSONObject.providerArrayOrNull(): JSONArray? {
        optJSONArray("providers")?.let { return it }
        optJSONArray("providerStatuses")?.let { return it }
        optJSONArray("status")?.let { return it }
        optJSONObject("data")?.providerArrayOrNull()?.let { return it }
        optJSONObject("result")?.providerArrayOrNull()?.let { return it }
        return null
    }

    private fun JSONObject.toGatewayProviderStatus(
        index: Int,
        fetchedAtMillis: Long
    ): GatewayProviderStatus {
        val id = firstString("id", "providerId", "provider_id", "name")
            ?: "provider-$index"
        val type = firstString("type", "providerType", "provider_type", "kind")
            ?: "unknown"
        val displayName = firstString("displayName", "display_name", "label", "name")
            ?: id
        val status = firstString("status", "state", "health")
            ?: "unknown"
        val activeModel = firstString("activeModel", "active_model", "model", "modelName", "model_name")
        val lastLatencyMs = firstLong("lastLatencyMs", "last_latency_ms", "latencyMs", "latency_ms", "latency")
        val lastCheckedAt = firstLong("lastCheckedAt", "last_checked_at", "checkedAt", "checked_at")
            ?: fetchedAtMillis

        return GatewayProviderStatus(
            id = id,
            type = type,
            displayName = displayName,
            status = status,
            activeModel = activeModel,
            lastLatencyMs = lastLatencyMs,
            lastCheckedAt = lastCheckedAt
        )
    }

    private fun JSONObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            optString(key, "").trim().takeIf { it.isNotEmpty() }
        }

    private fun JSONObject.firstLong(vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key ->
            if (!has(key) || isNull(key)) {
                null
            } else {
                val value = opt(key)
                when (value) {
                    is Number -> value.toLong()
                    is String -> value.toLongOrNull()
                    else -> null
                }
            }
        }
}

sealed class GatewayProviderStatusException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    class Auth(message: String) : GatewayProviderStatusException(message)
    class Malformed(message: String, cause: Throwable? = null) :
        GatewayProviderStatusException(message, cause)
}
