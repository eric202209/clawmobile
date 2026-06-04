package com.user.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewaySessionMapperTest {

    @Test
    fun fromApi_preservesSessionListFieldsForCache() {
        val item = MobileSessionListItem(
            id = 42,
            name = "Build mobile slice",
            status = "running",
            isActive = true,
            projectId = 7,
            startedAt = "2026-06-03T12:00:00Z",
            stoppedAt = null
        )

        val cached = GatewaySessionMapper.fromApi(item, cachedAtMillis = 1234L)

        assertEquals("42", cached.id)
        assertEquals("Build mobile slice", cached.name)
        assertEquals("7", cached.projectId)
        assertEquals("running", cached.status)
        assertEquals(true, cached.isActive)
        assertEquals(1234L, cached.cachedAt)
    }

    @Test
    fun toApiListItem_keepsRunningSessionOpen() {
        val cached = GatewaySession(
            id = "42",
            gatewayId = "42",
            name = "Build mobile slice",
            projectId = "7",
            startedAt = 1_801_484_800_000L,
            status = "running",
            isActive = true,
            messageCount = 0,
            lastActivity = 1_801_488_400_000L,
            cachedAt = 1_801_488_400_000L
        )

        val item = GatewaySessionMapper.toApiListItem(cached)

        assertEquals(42, item.id)
        assertEquals("Build mobile slice", item.name)
        assertEquals(7, item.projectId)
        assertEquals("running", item.status)
        assertEquals(true, item.isActive)
        assertNull(item.stoppedAt)
    }
}
