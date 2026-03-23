package com.user.service

import com.user.data.ChatDao
import com.user.data.ChatMessage
import com.user.data.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface OpenClawApi {
    @POST("api/messages/send")
    suspend fun sendMessage(
        @Header("Authorization") auth: String,
        @Body request: SendMessageRequest
    ): SendMessageResponse

    // ── Image upload ──────────────────────────────────────────
    // POST /api/upload — handled by llama-proxy or openclaw gateway
    // proxy saves the file to workspace/photos/ and returns the path
    @POST("api/upload")
    suspend fun uploadImage(
        @Header("Authorization") auth: String,
        @Body request: UploadImageRequest
    ): UploadImageResponse
}

// ── Upload request/response ───────────────────────────────────
data class UploadImageRequest(
    val filename: String,   // e.g. "photo_1234567890.jpg"
    val data: String,       // base64-encoded image bytes
    val mimeType: String    // e.g. "image/jpeg"
)

data class UploadImageResponse(
    val success: Boolean,
    val path: String? = null,       // absolute path on server e.g. /root/.openclaw/workspace/photos/photo_xxx.jpg
    val filename: String? = null,
    val mimeType: String? = null,
    val error: String? = null
)

// ── Chat message request/response ────────────────────────────
data class SendMessageRequest(
    val sessionId: String,
    val message: String,
    val timestamp: Long
)

data class SendMessageResponse(
    val success: Boolean,
    val response: String? = null,
    val error: String? = null
)

// ─────────────────────────────────────────────────────────────

class OpenClawService(
    private val chatDao: ChatDao,
    private val prefs: PrefsManager
) {
    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun getApi(): OpenClawApi {
        val baseUrl = prefs.serverUrl
        val retrofit = Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(OpenClawApi::class.java)
    }

    // ── Send text message ─────────────────────────────────────
    suspend fun sendMessage(sessionId: String, message: String): SendMessageResponse {
        return withContext(Dispatchers.IO) {
            try {
                getApi().sendMessage(
                    "Bearer ${prefs.gatewayToken}",
                    SendMessageRequest(sessionId, message, System.currentTimeMillis())
                )
            } catch (e: Exception) {
                SendMessageResponse(false, error = e.message ?: "Unknown error")
            }
        }
    }

    // ── Upload image to proxy → workspace/photos/ ─────────────
    suspend fun uploadImage(
        filename: String,
        base64Data: String,
        mimeType: String = "image/jpeg"
    ): UploadImageResponse {
        return withContext(Dispatchers.IO) {
            try {
                getApi().uploadImage(
                    "Bearer ${prefs.gatewayToken}",
                    UploadImageRequest(filename, base64Data, mimeType)
                )
            } catch (e: Exception) {
                UploadImageResponse(
                    success = false,
                    error   = e.message ?: "Upload failed"
                )
            }
        }
    }

    // ── Room helpers ──────────────────────────────────────────
    suspend fun saveMessageToLocal(message: ChatMessage): Long {
        return withContext(Dispatchers.IO) { chatDao.insertMessage(message) }
    }

    suspend fun updateMessageContent(id: Long, content: String) {
        withContext(Dispatchers.IO) { chatDao.updateMessageContent(id, content) }
    }
}
