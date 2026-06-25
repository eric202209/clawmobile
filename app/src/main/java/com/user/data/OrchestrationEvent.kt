package com.user.data

import com.google.gson.annotations.SerializedName

data class OrchestrationEvent(
    val type: String = "orchestration_event",
    @SerializedName("event_id") val eventId: String? = null,
    @SerializedName("event_type") val eventType: String = "",
    @SerializedName("session_id") val sessionId: Int = 0,
    @SerializedName("task_id") val taskId: Int? = null,
    val phase: String? = null,
    val coordinator: String? = null,
    val timestamp: String? = null,
)
