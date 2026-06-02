package com.user.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ProviderStatusDao {
    @Upsert
    suspend fun upsertAll(statuses: List<GatewayProviderStatus>)

    @Query("SELECT * FROM gateway_provider_status ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun getAll(): List<GatewayProviderStatus>

    @Query(
        "SELECT * FROM gateway_provider_status " +
            "WHERE lower(status) IN ('active', 'available', 'connected', 'healthy', 'online', 'ready') " +
            "ORDER BY displayName COLLATE NOCASE ASC"
    )
    suspend fun getActiveProviders(): List<GatewayProviderStatus>

    @Query("DELETE FROM gateway_provider_status WHERE lastCheckedAt < :cutoffMillis")
    suspend fun deleteStale(cutoffMillis: Long): Int
}
