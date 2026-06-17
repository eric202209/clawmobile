package com.user.viewmodel

enum class GuidancePriority(val value: Int, val label: String) {
    LOW(20, "Low"),
    NORMAL(50, "Normal"),
    HIGH(100, "High");

    companion object {
        fun fromLabel(label: String): GuidancePriority =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: NORMAL
    }
}

object HumanGuidanceViewModel {

    fun buildCreatePayload(
        message: String,
        scope: String = "project",
        priority: GuidancePriority = GuidancePriority.NORMAL,
        backendTargets: List<String> = listOf("all"),
        modelTargets: List<String> = listOf("all"),
        purposeTargets: List<String> = listOf("all"),
        expiresAt: String? = null,
    ): Map<String, Any> {
        val payload = mutableMapOf<String, Any>(
            "message" to message,
            "scope" to scope,
            "priority" to priority.value,
            "backend_targets" to backendTargets,
            "model_targets" to modelTargets,
            "purpose_targets" to purposeTargets,
        )
        if (expiresAt != null) payload["expires_at"] = expiresAt
        return payload
    }

    fun buildActivationPayload(
        tableEnabled: Boolean,
        persistenceEnabled: Boolean,
        renderEnabled: Boolean,
        injectionEnabled: Boolean,
        conflictDetectionEnabled: Boolean,
    ): Map<String, Any> = mapOf(
        "table_enabled" to tableEnabled,
        "persistence_enabled" to persistenceEnabled,
        "render_enabled" to renderEnabled,
        "injection_enabled" to injectionEnabled,
        "conflict_detection_enabled" to conflictDetectionEnabled,
    )

    fun buildEnableAllPayload(): Map<String, Any> = buildActivationPayload(
        tableEnabled = true,
        persistenceEnabled = true,
        renderEnabled = true,
        injectionEnabled = true,
        conflictDetectionEnabled = true,
    )

    fun buildConflictResolvePayload(): Map<String, Any> = mapOf("status" to "resolved")
    fun buildConflictIgnorePayload(): Map<String, Any> = mapOf("status" to "ignored")
}
