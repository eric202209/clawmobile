package com.user.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseClientTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeEvent(
        eventType: String = "step_finished",
        eventId: String? = null,
        sessionId: Int = 42,
    ) = OrchestrationEvent(eventId = eventId, eventType = eventType, sessionId = sessionId)

    private fun eventJson(
        eventType: String = "phase_started",
        eventId: String? = "eid-1",
        sessionId: Int = 42,
    ): String {
        val idPart = if (eventId != null) "\"event_id\":\"$eventId\"," else ""
        return """{"type":"orchestration_event",${idPart}"event_type":"$eventType","session_id":$sessionId}"""
    }

    // ── parseSseFrame ─────────────────────────────────────────────────────────

    @Test
    fun parseSseFrame_eventAndDataLines_returnsEvent() {
        val lines = listOf(
            "event: orchestration_event",
            "data: ${eventJson(eventType = "phase_started", eventId = "eid-1")}",
        )
        val event = SseStreamHelper.parseSseFrame(lines)
        assertNotNull(event)
        assertEquals("phase_started", event!!.eventType)
        assertEquals("eid-1", event.eventId)
        assertEquals(42, event.sessionId)
    }

    @Test
    fun parseSseFrame_dataLineWithoutEventLine_returnsEvent() {
        val lines = listOf("data: ${eventJson(eventType = "step_finished", eventId = "s1")}")
        val event = SseStreamHelper.parseSseFrame(lines)
        assertNotNull(event)
        assertEquals("step_finished", event!!.eventType)
    }

    @Test
    fun parseSseFrame_heartbeatCommentOnly_returnsNull() {
        val event = SseStreamHelper.parseSseFrame(listOf(": heartbeat"))
        assertNull(event)
    }

    @Test
    fun parseSseFrame_noDataLine_returnsNull() {
        val event = SseStreamHelper.parseSseFrame(listOf("event: orchestration_event"))
        assertNull(event)
    }

    @Test
    fun parseSseFrame_malformedJson_returnsNullWithoutThrowing() {
        val event = SseStreamHelper.parseSseFrame(listOf("data: {not valid json {{{{"))
        assertNull(event)
    }

    @Test
    fun parseSseFrame_emptyDataValue_returnsNull() {
        val event = SseStreamHelper.parseSseFrame(listOf("data: "))
        assertNull(event)
    }

    @Test
    fun parseSseFrame_emptyLines_returnsNull() {
        val event = SseStreamHelper.parseSseFrame(emptyList())
        assertNull(event)
    }

    // ── shouldSendEvent / dedup ───────────────────────────────────────────────

    @Test
    fun shouldSendEvent_noEventId_alwaysPassesThrough() {
        val seen = LinkedHashMap<String, Unit>()
        val event = makeEvent(eventId = null)
        assertTrue(SseStreamHelper.shouldSendEvent(event, seen))
        assertTrue(SseStreamHelper.shouldSendEvent(event, seen))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun shouldSendEvent_firstOccurrence_returnsTrue() {
        val seen = LinkedHashMap<String, Unit>()
        assertTrue(SseStreamHelper.shouldSendEvent(makeEvent(eventId = "abc-1"), seen))
        assertEquals(1, seen.size)
    }

    @Test
    fun shouldSendEvent_duplicate_returnsFalse() {
        val seen = LinkedHashMap<String, Unit>()
        SseStreamHelper.shouldSendEvent(makeEvent(eventId = "abc-2"), seen)
        assertFalse(SseStreamHelper.shouldSendEvent(makeEvent(eventId = "abc-2"), seen))
    }

    @Test
    fun shouldSendEvent_differentIds_bothPass() {
        val seen = LinkedHashMap<String, Unit>()
        assertTrue(SseStreamHelper.shouldSendEvent(makeEvent(eventId = "id-a"), seen))
        assertTrue(SseStreamHelper.shouldSendEvent(makeEvent(eventId = "id-b"), seen))
        assertEquals(2, seen.size)
    }

    @Test
    fun shouldSendEvent_lruEviction_allowsOldestIdAfterCapacityExceeded() {
        val seen = LinkedHashMap<String, Unit>()
        val maxSize = 3
        for (i in 1..maxSize) {
            SseStreamHelper.shouldSendEvent(makeEvent(eventId = "id-$i"), seen, maxSize)
        }
        assertEquals(maxSize, seen.size)
        // Adding "id-4" evicts "id-1"
        SseStreamHelper.shouldSendEvent(makeEvent(eventId = "id-4"), seen, maxSize)
        assertEquals(maxSize, seen.size)
        // "id-1" was evicted; it passes through again
        assertTrue(SseStreamHelper.shouldSendEvent(makeEvent(eventId = "id-1"), seen, maxSize))
    }

    @Test
    fun shouldSendEvent_seenIdsNeverExceedsMaxSize() {
        val seen = LinkedHashMap<String, Unit>()
        val maxSize = 5
        repeat(20) { i ->
            SseStreamHelper.shouldSendEvent(makeEvent(eventId = "id-$i"), seen, maxSize)
        }
        assertTrue(seen.size <= maxSize)
    }

    // ── isLifecycleRelevant ───────────────────────────────────────────────────

    @Test
    fun isLifecycleRelevant_phaseStarted_true() =
        assertTrue(SseStreamHelper.isLifecycleRelevant(makeEvent(eventType = "phase_started")))

    @Test
    fun isLifecycleRelevant_phaseFinished_true() =
        assertTrue(SseStreamHelper.isLifecycleRelevant(makeEvent(eventType = "phase_finished")))

    @Test
    fun isLifecycleRelevant_taskCompleted_true() =
        assertTrue(SseStreamHelper.isLifecycleRelevant(makeEvent(eventType = "task_completed")))

    @Test
    fun isLifecycleRelevant_taskFailed_true() =
        assertTrue(SseStreamHelper.isLifecycleRelevant(makeEvent(eventType = "task_failed")))

    @Test
    fun isLifecycleRelevant_humanInterventionRequested_true() =
        assertTrue(SseStreamHelper.isLifecycleRelevant(makeEvent(eventType = "human_intervention_requested")))

    @Test
    fun isLifecycleRelevant_sessionPaused_true() =
        assertTrue(SseStreamHelper.isLifecycleRelevant(makeEvent(eventType = "session_paused")))

    @Test
    fun isLifecycleRelevant_sessionResumed_true() =
        assertTrue(SseStreamHelper.isLifecycleRelevant(makeEvent(eventType = "session_resumed")))

    @Test
    fun isLifecycleRelevant_sessionStopped_true() =
        assertTrue(SseStreamHelper.isLifecycleRelevant(makeEvent(eventType = "session_stopped")))

    @Test
    fun isLifecycleRelevant_stepFinished_false() =
        assertFalse(SseStreamHelper.isLifecycleRelevant(makeEvent(eventType = "step_finished")))

    @Test
    fun isLifecycleRelevant_stepStarted_false() =
        assertFalse(SseStreamHelper.isLifecycleRelevant(makeEvent(eventType = "step_started")))

    @Test
    fun isLifecycleRelevant_unknownType_false() =
        assertFalse(SseStreamHelper.isLifecycleRelevant(makeEvent(eventType = "some_unknown_type")))

    @Test
    fun isLifecycleRelevant_emptyEventType_false() =
        assertFalse(SseStreamHelper.isLifecycleRelevant(makeEvent(eventType = "")))

    // ── backoffMs / reconnect policy ─────────────────────────────────────────

    @Test
    fun backoffMs_attempt1_returnsBaseBackoff() =
        assertEquals(SseStreamHelper.BASE_BACKOFF_MS, SseStreamHelper.backoffMs(1))

    @Test
    fun backoffMs_attempt2_doublesBackoff() =
        assertEquals(SseStreamHelper.BASE_BACKOFF_MS * 2, SseStreamHelper.backoffMs(2))

    @Test
    fun backoffMs_attempt3_quadruplesBackoff() =
        assertEquals(SseStreamHelper.BASE_BACKOFF_MS * 4, SseStreamHelper.backoffMs(3))

    @Test
    fun backoffMs_highAttempt_capsAtMaxBackoff() =
        assertEquals(SseStreamHelper.MAX_BACKOFF_MS, SseStreamHelper.backoffMs(20))

    @Test
    fun backoffMs_neverExceedsMaxMs() {
        for (attempt in 1..100) {
            assertTrue(SseStreamHelper.backoffMs(attempt) <= SseStreamHelper.MAX_BACKOFF_MS)
        }
    }

    @Test
    fun maxAttempts_constant_isFive() =
        assertEquals(5, SseStreamHelper.MAX_ATTEMPTS)
}
