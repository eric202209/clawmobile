package com.user.service

import android.util.Log
import com.google.gson.Gson
import com.user.data.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class LogEntry(
    val level: String = "INFO",
    val message: String = "",
    val timestamp: String = ""
)

class WebSocketManager(private val prefs: PrefsManager) {

    private companion object {
        const val TAG = "WebSocketManager"
        const val BASE_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_ATTEMPTS = 5
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // no read timeout for streaming
        .build()

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _logStream = MutableSharedFlow<LogEntry>(extraBufferCapacity = 64)
    val logStream: SharedFlow<LogEntry> = _logStream

    private var webSocket: WebSocket? = null
    private val connected = AtomicBoolean(false)
    private val attemptCount = AtomicInteger(0)
    private var reconnectJob: Job? = null

    var onReconnecting: ((attempt: Int) -> Unit)? = null
    var onMaxAttemptsReached: (() -> Unit)? = null

    fun connect(sessionId: String) {
        attemptCount.set(0)
        doConnect(sessionId)
    }

    private fun doConnect(sessionId: String) {
        val rawBase = prefs.orchestratorServerUrl.trim().trimEnd('/')
        val base = when {
            rawBase.endsWith("/api/v1/mobile") -> rawBase.removeSuffix("/api/v1/mobile")
            rawBase.endsWith("/api/v1") -> rawBase.removeSuffix("/api/v1")
            rawBase.endsWith("/mobile") -> rawBase.removeSuffix("/mobile")
            else -> rawBase
        }
        val wsBase = base.replace("http://", "ws://").replace("https://", "wss://")
        val apiKey = prefs.orchestratorApiKey.ifBlank { prefs.gatewayToken }
        val url = "$wsBase/api/v1/mobile/sessions/$sessionId/logs/stream"

        Log.d(TAG, "Connecting to WebSocket: $url")
        val request = Request.Builder()
            .url(url)
            .addHeader("X-OpenClaw-API-Key", apiKey)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                connected.set(true)
                attemptCount.set(0)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val entry = runCatching { gson.fromJson(text, LogEntry::class.java) }
                    .getOrElse { LogEntry(message = text) }
                scope.launch { _logStream.emit(entry) }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                connected.set(false)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                connected.set(false)
                scheduleReconnect(sessionId)
            }
        })
    }

    private fun scheduleReconnect(sessionId: String) {
        val attempt = attemptCount.incrementAndGet()
        if (attempt > MAX_ATTEMPTS) {
            Log.w(TAG, "WebSocket max reconnect attempts reached")
            onMaxAttemptsReached?.invoke()
            return
        }
        onReconnecting?.invoke(attempt)
        val backoff = minOf(BASE_BACKOFF_MS * (1L shl (attempt - 1)), MAX_BACKOFF_MS)
        Log.d(TAG, "Scheduling reconnect attempt $attempt in ${backoff}ms")
        reconnectJob = scope.launch {
            delay(backoff)
            doConnect(sessionId)
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "Lifecycle stop")
        webSocket = null
        connected.set(false)
        attemptCount.set(0)
    }

    fun isConnected() = connected.get()
}
