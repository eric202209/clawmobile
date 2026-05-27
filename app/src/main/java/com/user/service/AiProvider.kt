package com.user.service

import com.user.data.ChatMessage

interface AiProvider {
    val id: String
    val displayName: String
    suspend fun healthCheck(): Result<Unit>
    suspend fun sendMessage(message: String, history: List<ChatMessage>): Result<String>
}
