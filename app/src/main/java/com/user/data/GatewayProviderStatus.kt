package com.user.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gateway_provider_status")
data class GatewayProviderStatus(
    @PrimaryKey
    val id: String,
    val type: String,
    val displayName: String,
    val status: String,
    val activeModel: String?,
    val lastLatencyMs: Long?,
    val lastCheckedAt: Long
)
