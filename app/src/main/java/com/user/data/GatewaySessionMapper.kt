package com.user.data

import java.time.Instant

object GatewaySessionMapper {
    fun fromApi(item: MobileSessionListItem, cachedAtMillis: Long): GatewaySession =
        GatewaySession(
            id = item.id.toString(),
            gatewayId = item.id.toString(),
            name = item.name,
            projectId = if (item.projectId != 0) item.projectId.toString() else null,
            startedAt = parseIso8601(item.startedAt) ?: cachedAtMillis,
            status = item.status,
            isActive = item.isActive,
            messageCount = 0,
            lastActivity = parseIso8601(item.stoppedAt ?: item.startedAt) ?: cachedAtMillis,
            cachedAt = cachedAtMillis
        )

    fun toApiListItem(entity: GatewaySession): MobileSessionListItem =
        MobileSessionListItem(
            id = entity.id.toIntOrNull() ?: 0,
            name = entity.name,
            status = entity.status,
            isActive = entity.isActive,
            projectId = entity.projectId?.toIntOrNull() ?: 0,
            startedAt = Instant.ofEpochMilli(entity.startedAt).toString(),
            stoppedAt = if (entity.status.lowercase() in activeStatuses) {
                null
            } else {
                Instant.ofEpochMilli(entity.lastActivity).toString()
            }
        )

    private fun parseIso8601(value: String?): Long? {
        value ?: return null
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    }

    private val activeStatuses = setOf("running", "pending", "paused", "awaiting_input")
}
