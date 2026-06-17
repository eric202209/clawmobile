package com.user.viewmodel

import com.user.data.HumanGuidanceConflict
import com.user.data.HumanGuidanceEntry
import com.user.data.HumanGuidanceReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanGuidanceViewModelTest {

    @Test
    fun lowPriorityMapsTo20() {
        assertEquals(20, GuidancePriority.LOW.value)
    }

    @Test
    fun normalPriorityMapsTo50() {
        assertEquals(50, GuidancePriority.NORMAL.value)
    }

    @Test
    fun highPriorityMapsTo100() {
        assertEquals(100, GuidancePriority.HIGH.value)
    }

    @Test
    fun fromLabelHighReturnsHighPriority() {
        assertEquals(GuidancePriority.HIGH, GuidancePriority.fromLabel("High"))
    }

    @Test
    fun fromLabelUnknownFallsBackToNormal() {
        assertEquals(GuidancePriority.NORMAL, GuidancePriority.fromLabel("unknown"))
    }

    @Test
    fun defaultPayloadHasAllTargets() {
        val payload = HumanGuidanceViewModel.buildCreatePayload(message = "test")
        assertEquals(listOf("all"), payload["backend_targets"])
        assertEquals(listOf("all"), payload["model_targets"])
        assertEquals(listOf("all"), payload["purpose_targets"])
    }

    @Test
    fun defaultPayloadHasNormalPriority() {
        val payload = HumanGuidanceViewModel.buildCreatePayload(message = "test")
        assertEquals(50, payload["priority"])
    }

    @Test
    fun highPriorityPayloadHas100() {
        val payload = HumanGuidanceViewModel.buildCreatePayload(
            message = "urgent",
            priority = GuidancePriority.HIGH,
        )
        assertEquals(100, payload["priority"])
    }

    @Test
    fun enableAllPayloadSetsAllFlagsTrue() {
        val payload = HumanGuidanceViewModel.buildEnableAllPayload()
        assertTrue(payload["table_enabled"] as Boolean)
        assertTrue(payload["persistence_enabled"] as Boolean)
        assertTrue(payload["render_enabled"] as Boolean)
        assertTrue(payload["injection_enabled"] as Boolean)
        assertTrue(payload["conflict_detection_enabled"] as Boolean)
    }

    @Test
    fun activationPayloadCanDisableAll() {
        val payload = HumanGuidanceViewModel.buildActivationPayload(
            tableEnabled = false,
            persistenceEnabled = false,
            renderEnabled = false,
            injectionEnabled = false,
            conflictDetectionEnabled = false,
        )
        assertFalse(payload["table_enabled"] as Boolean)
        assertFalse(payload["injection_enabled"] as Boolean)
    }

    @Test
    fun resolveConflictPayloadHasStatusResolved() {
        val payload = HumanGuidanceViewModel.buildConflictResolvePayload()
        assertEquals("resolved", payload["status"])
    }

    @Test
    fun ignoreConflictPayloadHasStatusIgnored() {
        val payload = HumanGuidanceViewModel.buildConflictIgnorePayload()
        assertEquals("ignored", payload["status"])
    }

    @Test
    fun readyStateHasCorrectGuidanceCount() {
        val readiness = HumanGuidanceReadiness(projectId = 1, activeGuidanceCount = 5, isReady = true)
        val state = HumanGuidanceUiState.Ready(
            readiness = readiness,
            guidance = emptyList(),
            conflicts = emptyList(),
        )
        assertEquals(5, state.readiness.activeGuidanceCount)
        assertTrue(state.readiness.isReady)
    }

    @Test
    fun readyStateDefaultsToBasicMode() {
        val readiness = HumanGuidanceReadiness(projectId = 1)
        val state = HumanGuidanceUiState.Ready(
            readiness = readiness,
            guidance = emptyList(),
            conflicts = emptyList(),
        )
        assertFalse(state.isAdvancedMode)
    }

    @Test
    fun errorStateHasMessage() {
        val state = HumanGuidanceUiState.Error("Network error")
        assertEquals("Network error", state.message)
    }

    @Test
    fun readyStateHasGuidanceList() {
        val entry = HumanGuidanceEntry(id = 1, message = "Do not use X", priority = 50)
        val readiness = HumanGuidanceReadiness(projectId = 42)
        val state = HumanGuidanceUiState.Ready(
            readiness = readiness,
            guidance = listOf(entry),
            conflicts = emptyList(),
        )
        assertEquals(1, state.guidance.size)
        assertEquals("Do not use X", state.guidance[0].message)
    }

    @Test
    fun readyStateHasConflictList() {
        val conflict = HumanGuidanceConflict(id = 3, guidanceMessage = "Never use X", taskTitle = "Task A")
        val readiness = HumanGuidanceReadiness(projectId = 42)
        val state = HumanGuidanceUiState.Ready(
            readiness = readiness,
            guidance = emptyList(),
            conflicts = listOf(conflict),
        )
        assertEquals(1, state.conflicts.size)
        assertEquals("Task A", state.conflicts[0].taskTitle)
    }

    @Test
    fun createPayloadIncludesMessageAndScope() {
        val payload = HumanGuidanceViewModel.buildCreatePayload(
            message = "Always validate inputs",
            scope = "global",
        )
        assertEquals("Always validate inputs", payload["message"])
        assertEquals("global", payload["scope"])
    }

    @Test
    fun createPayloadWithExpiresAtIncludesField() {
        val payload = HumanGuidanceViewModel.buildCreatePayload(
            message = "Temporary rule",
            expiresAt = "2026-12-31T00:00:00Z",
        )
        assertEquals("2026-12-31T00:00:00Z", payload["expires_at"])
    }
}
