package com.user.data

import com.google.gson.annotations.SerializedName

data class HumanGuidanceEntry(
    val id: Int = 0,
    @SerializedName("project_id") val projectId: Int? = null,
    @SerializedName("session_id") val sessionId: Int? = null,
    @SerializedName("task_id") val taskId: Int? = null,
    val scope: String = "project",
    val message: String = "",
    val status: String = "active",
    val priority: Int = 50,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("expires_at") val expiresAt: String? = null,
    @SerializedName("created_by") val createdBy: String? = null,
    val revision: Int = 1,
    @SerializedName("backend_targets") val backendTargets: List<String> = listOf("all"),
    @SerializedName("model_targets") val modelTargets: List<String> = listOf("all"),
    @SerializedName("purpose_targets") val purposeTargets: List<String> = listOf("all"),
)

data class HumanGuidanceActivationData(
    val id: Int? = null,
    val scope: String? = null,
    @SerializedName("project_id") val projectId: Int? = null,
    @SerializedName("session_id") val sessionId: Int? = null,
    @SerializedName("table_enabled") val tableEnabled: Boolean = false,
    @SerializedName("persistence_enabled") val persistenceEnabled: Boolean = false,
    @SerializedName("render_enabled") val renderEnabled: Boolean = false,
    @SerializedName("injection_enabled") val injectionEnabled: Boolean = false,
    @SerializedName("conflict_detection_enabled") val conflictDetectionEnabled: Boolean = false,
    val status: String = "inactive",
    @SerializedName("enabled_by") val enabledBy: String? = null,
    @SerializedName("disabled_at") val disabledAt: String? = null,
    @SerializedName("disabled_by") val disabledBy: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
)

data class HumanGuidancePurposeStats(
    val all: Int = 0,
    val planning: Int = 0,
    val execution: Int = 0,
    val repair: Int = 0,
    val validation: Int = 0,
)

data class HumanGuidanceBackendStats(
    val backend: String = "all",
    @SerializedName("model_family") val modelFamily: String = "all",
    @SerializedName("matching_guidance") val matchingGuidance: Int = 0,
    @SerializedName("filtered_guidance") val filteredGuidance: Int = 0,
    @SerializedName("filtered_ids") val filteredIds: List<Int> = emptyList(),
)

data class HumanGuidanceReadiness(
    @SerializedName("project_id") val projectId: Int = 0,
    @SerializedName("active_guidance_count") val activeGuidanceCount: Int = 0,
    @SerializedName("is_ready") val isReady: Boolean = false,
    val activation: HumanGuidanceActivationData? = null,
    @SerializedName("backend_statistics") val backendStatistics: HumanGuidanceBackendStats? = null,
    @SerializedName("purpose_statistics") val purposeStatistics: HumanGuidancePurposeStats = HumanGuidancePurposeStats(),
    val warnings: List<String> = emptyList(),
)

data class HumanGuidanceListResponse(
    @SerializedName("project_id") val projectId: Int = 0,
    val total: Int = 0,
    val items: List<HumanGuidanceEntry> = emptyList(),
)

data class HumanGuidanceRendered(
    @SerializedName("project_id") val projectId: Int = 0,
    @SerializedName("rendered_chars") val renderedChars: Int = 0,
    @SerializedName("max_chars") val maxChars: Int = 0,
    val trimmed: Boolean = false,
    @SerializedName("selected_count") val selectedCount: Int = 0,
    @SerializedName("trimmed_count") val trimmedCount: Int = 0,
    @SerializedName("selected_ids") val selectedIds: List<Int> = emptyList(),
    @SerializedName("trimmed_ids") val trimmedIds: List<Int> = emptyList(),
    val block: String = "",
    val backend: String = "all",
    @SerializedName("model_family") val modelFamily: String = "all",
    val purpose: String = "all",
)

data class HumanGuidanceConflict(
    val id: Int? = null,
    @SerializedName("guidance_id") val guidanceId: Int? = null,
    @SerializedName("guidance_message") val guidanceMessage: String = "",
    @SerializedName("task_id") val taskId: Int? = null,
    @SerializedName("task_title") val taskTitle: String = "",
    @SerializedName("conflict_excerpt") val conflictExcerpt: String = "",
    @SerializedName("conflict_patterns") val conflictPatterns: List<String> = emptyList(),
    val severity: String = "warning",
    val status: String = "open",
    @SerializedName("detected_at") val detectedAt: String? = null,
    val resolved: Boolean = false,
)

data class HumanGuidanceConflictListResponse(
    @SerializedName("project_id") val projectId: Int = 0,
    val total: Int = 0,
    val items: List<HumanGuidanceConflict> = emptyList(),
)
