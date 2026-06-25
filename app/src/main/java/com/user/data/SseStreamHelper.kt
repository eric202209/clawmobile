package com.user.data

import com.google.gson.Gson

/**
 * Pure SSE stream utilities — no Android imports, fully unit-testable.
 *
 * Used by SseClient (service layer) and directly testable from JVM unit tests.
 */
object SseStreamHelper {

    const val DEDUP_MAXSIZE = 512
    const val MAX_ATTEMPTS = 5
    const val BASE_BACKOFF_MS = 2_000L
    const val MAX_BACKOFF_MS = 30_000L

    private val gson = Gson()

    private val lifecycleEventTypes = setOf(
        "phase_started",
        "phase_finished",
        "task_completed",
        "task_failed",
        "human_intervention_requested",
        "session_paused",
        "session_resumed",
        "session_stopped",
        "session_completed",
    )

    /**
     * Parse one SSE frame (all non-blank lines between two blank lines).
     * Returns null for comment-only frames (heartbeat) or malformed JSON.
     */
    fun parseSseFrame(lines: List<String>): OrchestrationEvent? {
        val dataLine = lines
            .firstOrNull { it.startsWith("data:") }
            ?.removePrefix("data:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching { gson.fromJson(dataLine, OrchestrationEvent::class.java) }.getOrNull()
    }

    /**
     * True if this event type should trigger a session summary reload.
     */
    fun isLifecycleRelevant(event: OrchestrationEvent): Boolean =
        event.eventType in lifecycleEventTypes

    /**
     * Dedup guard. Returns true if the event should be forwarded.
     * Events without event_id are always forwarded.
     * Maintains insertion-ordered eviction (oldest evicted when seenIds exceeds maxSize).
     */
    fun shouldSendEvent(
        event: OrchestrationEvent,
        seenIds: LinkedHashMap<String, Unit>,
        maxSize: Int = DEDUP_MAXSIZE,
    ): Boolean {
        val id = event.eventId ?: return true
        if (id in seenIds) return false
        seenIds[id] = Unit
        if (seenIds.size > maxSize) {
            seenIds.iterator().also { it.next(); it.remove() }
        }
        return true
    }

    /**
     * Bounded exponential backoff for reconnect attempts (1-indexed).
     */
    fun backoffMs(attempt: Int, baseMs: Long = BASE_BACKOFF_MS, maxMs: Long = MAX_BACKOFF_MS): Long =
        minOf(baseMs * (1L shl (attempt - 1)), maxMs)
}
