package com.user.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gateway_sessions")
data class GatewaySession(
    @PrimaryKey val id: String,
    val gatewayId: String,
    val name: String,
    val projectId: String?,
    val startedAt: Long,
    val status: String,
    val isActive: Boolean,
    val messageCount: Int,
    val lastActivity: Long,
    val cachedAt: Long
)
