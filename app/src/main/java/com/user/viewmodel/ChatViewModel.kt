package com.user.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.user.data.ChatDatabase
import com.user.data.ChatMessage
import com.user.data.ChatSession
import com.user.data.PrefsManager
import com.user.repository.ChatRepository
import com.user.service.AgentInfo
import com.user.service.Ed25519Manager
import com.user.service.GatewayClient
import com.user.service.GatewayEvent
import com.user.service.OpenClawService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import com.user.service.GatewayConnectionService
import com.user.data.MessageStatus

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // ── Dependencies ──────────────────────────────────────────
    private val prefs         = PrefsManager(application)
    private val db            = ChatDatabase.getDatabase(application)
    private val repository    = ChatRepository(db.chatDao(), prefs)
    private val ed25519       = Ed25519Manager(application)
    private val gateway       = GatewayClient(prefs.serverUrl, prefs.gatewayToken, ed25519)
    private val openClawService = OpenClawService(db.chatDao(), prefs)

    // ── Session ───────────────────────────────────────────────
    private var _sessionId = UUID.randomUUID().toString()
    val sessionId get() = _sessionId

    // ── LiveData exposed to UI ────────────────────────────────
    private val _status = MutableLiveData("○ Disconnected")
    val status: LiveData<String> = _status

    private val _toast = MutableLiveData<String?>()
    val toast: LiveData<String?> = _toast

    private val _agents = MutableLiveData<List<AgentInfo>>(emptyList())
    val agents: LiveData<List<AgentInfo>> = _agents

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _isSending = MutableLiveData(false)
    val isSending: LiveData<Boolean> = _isSending

    private val _showTyping = MutableLiveData(false)
    val showTyping: LiveData<Boolean> = _showTyping

    private val _pairingRequired = MutableLiveData<String?>(null)
    val pairingRequired: LiveData<String?> = _pairingRequired

    // Track if we've shown the pairing dialog for a given device ID
    private var lastPairingDeviceId: String? = null

    // Track if pairing dialog was shown during camera/gallery use
    private var pairingDeferred = false
    private var deferredDeviceId: String? = null

    // Flag to indicate if camera/gallery is active (set from MainActivity)
    private val _isCameraOrGalleryActive = MutableLiveData(false)
    val isCameraOrGalleryActive: LiveData<Boolean> = _isCameraOrGalleryActive

    // Method to set camera/gallery active status from MainActivity
    fun setCameraOrGalleryActive(active: Boolean) {
        _isCameraOrGalleryActive.value = active
        // If we just finished using camera/gallery and a pairing request was deferred, show it now
        if (!active && pairingDeferred && deferredDeviceId != null) {
            _pairingRequired.postValue(deferredDeviceId)
            pairingDeferred = false
            deferredDeviceId = null
        }
    }

    // ── Streaming state ───────────────────────────────────────
    private var streamingMsgId: Long = -1L

    // Using a separate buffer in the ViewModel, without reading from LiveData.
    private val streamBuffer = StringBuilder()

    // Fix two GatewayClients connect simultaneously
    private var lastProcessedFullText: String = ""

    // ── Init ──────────────────────────────────────────────────
    init {
        // Sync PrefsManager deviceId with Ed25519Manager
        prefs.deviceId = ed25519.deviceId

        // Set up pairing complete callback to persist device ID
        gateway.onPairingComplete = { deviceId ->
            // Persist the device ID to prefs
            prefs.deviceId = deviceId
            // Also update Ed25519Manager's pairing status
            ed25519.persistDeviceId()
            // Signal the service to mark as paired
            signalServicePaired(deviceId)
            // Clear any deferred pairing notification
            pairingDeferred = false
            deferredDeviceId = null
            lastPairingDeviceId = null
            // Reconnect to verify pairing worked
            viewModelScope.launch {
                // Small delay to let the pairing complete
                kotlinx.coroutines.delay(500)
                gateway.connect()
            }
        }
        observeGatewayEvents()
    }

    // Signal the GatewayConnectionService that pairing is complete
    private fun signalServicePaired(deviceId: String) {
        val intent = Intent(getApplication(), GatewayConnectionService::class.java)
        intent.putExtra("action", "mark_paired")
        intent.putExtra("deviceId", deviceId)
        getApplication<Application>().startForegroundService(intent)
    }

    // ── Session management ────────────────────────────────────

    fun loadSession(sessionId: String?, sessionTitle: String?) {
        if (sessionId != null) {
            _sessionId = sessionId
        }
        observeMessages()
    }

    fun startNewSession() {
        _sessionId = UUID.randomUUID().toString()
        viewModelScope.launch {
            repository.insertSession(
                ChatSession(
                    sessionId = _sessionId,
                    title = "Chat ${SimpleDateFormat("MMM dd HH:mm",
                        Locale.getDefault()).format(Date())}"
                )
            )
        }
        observeMessages()
    }

    private fun observeMessages() {
        viewModelScope.launch {
            repository.getMessages(_sessionId).collectLatest { msgs ->
                _messages.postValue(msgs)
            }
        }
    }

    fun startService(context: Context) {
        val intent = Intent(context, GatewayConnectionService::class.java)
        context.startForegroundService(intent)
        // The ViewModel's own GatewayClient continues to handle UI updates.
        connect()
    }

    // ── Gateway ───────────────────────────────────────────────

    fun connect() {
        gateway.connect()
    }

    fun switchAgent(agentId: String) {
        gateway.switchAgent(agentId)
        val agentName = _agents.value?.find { it.agentId == agentId }?.name ?: agentId
        _status.postValue("● $agentName")
    }

    private fun observeGatewayEvents() {
        viewModelScope.launch {
            gateway.events.collect { event ->
                when (event) {
                    is GatewayEvent.Connecting ->
                        _toast.postValue("○ Connecting…")

                    is GatewayEvent.HandshakeStarted ->
                        _toast.postValue("● Handshaking…")

                    is GatewayEvent.Ready -> {
                        _toast.postValue("● Connected")
                        _agents.postValue(event.agents)
                        _isSending.postValue(false)
                        observeMessages()
                        updateServiceNotification("● Connected", event.agents.firstOrNull()?.name ?: "Main")
                    }

                    is GatewayEvent.PairingRequired -> {
                        // Only show pairing dialog if camera/gallery is not active
                        if (_isCameraOrGalleryActive.value != true) {
                            // If this is a new device ID or we deferred it, show the dialog
                            if (event.deviceId != lastPairingDeviceId || pairingDeferred) {
                                _pairingRequired.postValue(event.deviceId)
                                lastPairingDeviceId = event.deviceId
                                pairingDeferred = false
                                deferredDeviceId = null
                            }
                        } else {
                            // Camera/gallery is active, defer the pairing notification
                            pairingDeferred = true
                            deferredDeviceId = event.deviceId
                        }
                    }

                    is GatewayEvent.AuthError -> {
                        _toast.postValue("✕ Auth failed")
                    }

                    is GatewayEvent.StreamDelta -> {
                        _showTyping.postValue(false)
                        streamBuffer.append(event.text)
                        if (streamingMsgId == -1L) {
                            val placeholder = ChatMessage(
                                sessionId = _sessionId,
                                message   = streamBuffer.toString(),
                                isUser    = false,
                                status    = MessageStatus.STREAMING
                            )
                            streamingMsgId = repository.insertMessage(placeholder)
                        } else {
                            repository.updateMessageContent(
                                streamingMsgId,
                                streamBuffer.toString()
                            )
                        }
                    }

                    is GatewayEvent.StreamFinal -> {
                        if (event.fullText == lastProcessedFullText) return@collect
                        lastProcessedFullText = event.fullText
                        streamBuffer.clear()
                        if (streamingMsgId != -1L) {
                            repository.updateMessageContent(
                                streamingMsgId, event.fullText
                            )
                            repository.updateMessageStatus(streamingMsgId, MessageStatus.FINAL)
                            streamingMsgId = -1L
                        } else {
                            repository.insertMessage(
                                ChatMessage(
                                    sessionId = _sessionId,
                                    message   = event.fullText,
                                    isUser    = false
                                )
                            )
                        }
                        repository.updateSessionTime(_sessionId, System.currentTimeMillis())
                        _isSending.postValue(false)
                        _showTyping.postValue(false)
                    }

                    is GatewayEvent.ToolCall -> {
                        val icon = if (event.done) "✅" else "⚙️"
                        _toast.postValue("$icon ${event.name}")
                    }

                    is GatewayEvent.Disconnected -> {
                        _status.postValue("○ Disconnected")
                        updateServiceNotification("○ Disconnected", "")
                    }

                    is GatewayEvent.Error -> {
                        _toast.postValue("✕ ${event.message}")
                        _showTyping.postValue(false)
                        _isSending.postValue(false)
                        updateServiceNotification("✕ Error", "")
                    }
                }
            }
        }
    }

    fun clearToast() { _toast.postValue(null) }

    private fun updateServiceNotification(status: String, agent: String = "") {
        val intent = Intent(getApplication(), GatewayConnectionService::class.java).apply {
            putExtra("status", status)
            putExtra("agent", agent)
        }
        getApplication<Application>().startService(intent)
    }

    // ── Send message ──────────────────────────────────────────

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        _isSending.postValue(true)
        _showTyping.postValue(true)

        viewModelScope.launch {
            // Save user message locally
            repository.insertMessage(
                ChatMessage(sessionId = _sessionId, message = text, isUser = true)
            )
            repository.updateSessionTime(_sessionId, System.currentTimeMillis())

            // Send to Gateway
            gateway.sendMessage(text)
        }
    }

    // ── Send image ────────────────────────────────────────────
    fun sendImage(context: Context, uri: Uri, caption: String = "") {
        if (!gateway.isReady()) {
            _toast.postValue("○ Not connected — cannot send image")
            return
        }
        _isSending.postValue(true)
        _showTyping.postValue(true)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // ── Step 1: decode + compress ─────────────────
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open image")
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                val maxWidth = 1024
                val scaled = if (bitmap.width > maxWidth) {
                    val ratio = maxWidth.toFloat() / bitmap.width
                    android.graphics.Bitmap.createScaledBitmap(
                        bitmap,
                        maxWidth,
                        (bitmap.height * ratio).toInt(),
                        true
                    )
                } else bitmap

                val baos = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                val imageBytes = baos.toByteArray()
                val base64Str = android.util.Base64.encodeToString(
                    imageBytes, android.util.Base64.NO_WRAP
                )

                // ── Step 2: upload to proxy /api/upload ───────────
                val filename = "photo_${System.currentTimeMillis()}.jpg"
                val uploadResult = openClawService.uploadImage(
                    filename  = filename,
                    base64Data = base64Str,
                    mimeType  = "image/jpeg"
                )

                if (!uploadResult.success || uploadResult.path == null) {
                    val err = uploadResult.error ?: "Upload failed"
                    _toast.postValue("⚠️ Upload failed: $err")
                    _isSending.postValue(false)
                    _showTyping.postValue(false)
                    return@launch
                }

                val serverPath = uploadResult.path
                _toast.postValue("✅ Image saved to workspace")

                // ── Step 3: save locally for display ──────────
                val displayText = if (caption.isNotBlank()) caption else "📷 Image"
                repository.insertMessage(
                    com.user.data.ChatMessage(
                        sessionId   = _sessionId,
                        message     = displayText,
                        isUser      = true,
                        imageBase64 = base64Str
                    )
                )
                repository.updateSessionTime(_sessionId, System.currentTimeMillis())

                // ── Step 4: send path to gateway ──────────────
                val gatewayText = buildString {
                    append("[gemini] ")
                    if (caption.isNotBlank()) {
                        append(caption)
                        append(" ")
                    } else {
                        append("Please analyze this image. ")
                    }
                    append(serverPath)
                }
                gateway.sendMessage(text = gatewayText)

            } catch (e: Exception) {
                _toast.postValue("✕ Image error: ${e.message}")
                _isSending.postValue(false)
                _showTyping.postValue(false)
            }
        }
    }

    // ── Reconnection control ─────────────────────────────────
    fun pauseReconnect() {
        gateway.shouldReconnect = false
    }

    fun resumeReconnect() {
        gateway.shouldReconnect = true
        if (gateway.isReady()) return
        gateway.connect()
    }

    // Clear the pairing dialog to allow it to be shown again after pairing
    fun clearPairingDialog() {
        lastPairingDeviceId = null
        pairingDeferred = false
        deferredDeviceId = null
        _pairingRequired.postValue(null)
    }

    // ── Cleanup ───────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        gateway.disconnect()
    }
}
