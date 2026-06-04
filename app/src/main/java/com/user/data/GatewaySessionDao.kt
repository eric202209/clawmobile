package com.user.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface GatewaySessionDao {
    @Upsert
    suspend fun upsertAll(sessions: List<GatewaySession>)

    @Query("SELECT * FROM gateway_sessions ORDER BY lastActivity DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int, offset: Int): List<GatewaySession>

    @Query(
        "SELECT * FROM gateway_sessions WHERE lower(status) = lower(:status) " +
            "ORDER BY lastActivity DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun getPageByStatus(status: String, limit: Int, offset: Int): List<GatewaySession>

    @Query("SELECT COUNT(*) FROM gateway_sessions")
    suspend fun count(): Int

    @Query("DELETE FROM gateway_sessions WHERE startedAt < :cutoffMillis")
    suspend fun deleteExpired(cutoffMillis: Long): Int
}
