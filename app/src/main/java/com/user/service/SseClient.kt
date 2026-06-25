package com.user.service

import android.util.Log
import com.user.data.OrchestrationEvent
import com.user.data.PrefsManager
import com.user.data.SseStreamHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SSE client for GET /api/v1/mobile/sessions/{id}/events/stream.
 *
 * Lifecycle matches WebSocketManager: call connect() in onStart, disconnect() in onStop.
 * Events are emitted to [eventStream]. Use [SseStreamHelper.isLifecycleRelevant] to
 * decide which events warrant a UI refresh.
 *
 * On connection failure the client reconnects with bounded exponential backoff up to
 * [SseStreamHelper.MAX_ATTEMPTS] times, then calls [onMaxAttemptsReached] and stops.
 * Falling back to existing polling behavior is handled by the caller.
 */
class SseClient(private val prefs: PrefsManager) {

    private companion object {
        const val TAG = "SseClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _eventStream = MutableSharedFlow<OrchestrationEvent>(extraBufferCapacity = 64)
    val eventStream: SharedFlow<OrchestrationEvent> = _eventStream

    private val _connected = AtomicBoolean(false)
    private val _attemptCount = AtomicInteger(0)
    private var streamJob: Job? = null

    var onReconnecting: ((attempt: Int) -> Unit)? = null
    var onMaxAttemptsReached: (() -> Unit)? = null

    fun connect(sessionId: String) {
        _attemptCount.set(0)
        doConnect(sessionId)
    }

    private fun doConnect(sessionId: String) {
        streamJob?.cancel()
        streamJob = scope.launch {
            try {
                val url = buildUrl(sessionId)
                val apiKey = prefs.orchestratorApiKey.ifBlank { prefs.gatewayToken }
                Log.d(TAG, "Connecting SSE for session $sessionId")

                val request = Request.Builder()
                    .url(url)
                    .addHeader("X-OpenClaw-API-Key", apiKey)
                    .addHeader("Accept", "text/event-stream")
                    .build()

                val seenIds = LinkedHashMap<String, Unit>()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "SSE connect failed: ${response.code}")
                        _connected.set(false)
                        scheduleReconnect(sessionId)
                        return@use
                    }
                    _connected.set(true)
                    _attemptCount.set(0)
                    Log.d(TAG, "SSE connected for session $sessionId")

                    val reader = response.body!!.byteStream().bufferedReader()
                    val frameLines = mutableListOf<String>()
                    reader.forEachLine { line ->
                        when {
                            line.isBlank() -> {
                                if (frameLines.isNotEmpty()) {
                                    val event = SseStreamHelper.parseSseFrame(frameLines)
                                    if (event != null && SseStreamHelper.shouldSendEvent(event, seenIds)) {
                                        _eventStream.tryEmit(event)
                                    }
                                    frameLines.clear()
                                }
                            }
                            line.startsWith(":") -> { /* heartbeat / comment */ }
                            else -> frameLines.add(line)
                        }
                    }

                    _connected.set(false)
                    Log.d(TAG, "SSE stream closed for session $sessionId; scheduling reconnect")
                    scheduleReconnect(sessionId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "SSE error for session: ${e.message}")
                _connected.set(false)
                scheduleReconnect(sessionId)
            }
        }
    }

    private fun scheduleReconnect(sessionId: String) {
        val attempt = _attemptCount.incrementAndGet()
        if (attempt > SseStreamHelper.MAX_ATTEMPTS) {
            Log.w(TAG, "SSE max reconnect attempts reached for session $sessionId")
            onMaxAttemptsReached?.invoke()
            return
        }
        onReconnecting?.invoke(attempt)
        val backoff = SseStreamHelper.backoffMs(attempt)
        Log.d(TAG, "SSE reconnect attempt $attempt in ${backoff}ms")
        scope.launch {
            delay(backoff)
            doConnect(sessionId)
        }
    }

    fun disconnect() {
        streamJob?.cancel()
        streamJob = null
        _connected.set(false)
        _attemptCount.set(0)
    }

    fun isConnected(): Boolean = _connected.get()

    private fun buildUrl(sessionId: String): String {
        val rawBase = prefs.orchestratorServerUrl.trim().trimEnd('/')
        val base = when {
            rawBase.endsWith("/api/v1/mobile") -> rawBase.removeSuffix("/api/v1/mobile")
            rawBase.endsWith("/api/v1") -> rawBase.removeSuffix("/api/v1")
            rawBase.endsWith("/mobile") -> rawBase.removeSuffix("/mobile")
            else -> rawBase
        }
        return "$base/api/v1/mobile/sessions/$sessionId/events/stream"
    }
}
