package com.user.data

import com.google.gson.annotations.SerializedName

/**
 * Recent activity item from Orchestrator API
 */
data class RecentActivity(
    @SerializedName("level")
    val level: String = "INFO",
    val message: String = "",
    val timestamp: String = "",
    @SerializedName("session_id")
    val sessionId: Int = 0
)

/**
 * Dashboard summary from Orchestrator API
 */
data class DashboardSummary(
    val projects: Int,
    val sessions: OrchestratorSessionStats,
    val tasks: OrchestratorTaskStats
)

data class DashboardResponse(
    val summary: DashboardSummary? = null,
    @SerializedName("recent_activity")
    val recentActivity: List<RecentActivity> = emptyList()
)

/**
 * Session statistics from Orchestrator API response
 *
 * Orchestrator returns: { "total": N, "active": N, "running": N }
 */
data class OrchestratorSessionStats(
    val total: Int = 0,
    @SerializedName("active")
    val active: Int = 0,
    // Support both 'running' and 'inProgress' field names
    @SerializedName("running")
    val running: Int = 0,
    @SerializedName("inProgress")
    val inProgress: Int = 0,
    // Support both 'completed' (legacy) and 'active' field names
    @SerializedName("completed")
    val completed: Int = 0
)

/**
 * Task statistics from Orchestrator API response
 *
 * Orchestrator returns: { "total": N, "done": N, "running": N, "failed": N, "completion_rate": "X.X%" }
 */
data class OrchestratorTaskStats(
    val total: Int = 0,
    val pending: Int = 0,
    // Support both 'inProgress' and 'running' field names from orchestrator
    @SerializedName("inProgress")
    val inProgress: Int = 0,
    @SerializedName("running")
    val running: Int = 0,
    val approved: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    // Use snake_case as returned by Orchestrator API
    @SerializedName("completion_rate")
    val completionRate: String = "N/A"
)

/**
 * Project from Orchestrator API
 * Supports both 'id' (from orchestrator) and 'projectId' formats
 */
data class Project(
    // Support both field names that orchestrator might use
    @SerializedName("id")
    val id: String = "",
    @SerializedName("projectId")
    val _projectId: String = "",
    val name: String,
    val description: String? = null,
    @SerializedName("github_url")
    val githubUrl: String? = null,
    val branch: String = "main",
    val status: String = "unknown",
    @SerializedName("created_at")
    val createdAt: String = "",
    @SerializedName("updated_at")
    val updatedAt: String = ""
) {
    // Return id if set, otherwise projectId for backwards compatibility
    fun getProjectId(): String = if (id.isNotEmpty()) id else _projectId
}

data class MobileProjectsResponse(
    val projects: List<Project> = emptyList(),
    val total: Int = 0
)

/**
 * Task from Orchestrator API (internal model matching exact API response format)
 */
data class OrchestTaskResponse(
    @SerializedName("id")
    val id: Int,
    val title: String,
    val description: String? = null,
    val status: String,
    @SerializedName("project_id")
    val projectId: Int,
    @SerializedName("created_at")
    val createdAt: String,
    @SerializedName("updated_at")
    val updatedAt: String? = null,
    val priority: Int = 0,
    @SerializedName("sequence_index")
    val sequenceIndex: Int? = null,
    @SerializedName("sequence_total")
    val sequenceTotal: Int? = null,
    @SerializedName("latest_session_id")
    val latestSessionId: Int? = null,
    @SerializedName("latest_session_name")
    val latestSessionName: String? = null,
    @SerializedName("latest_session_status")
    val latestSessionStatus: String? = null,
    @SerializedName("has_active_session")
    val hasActiveSession: Boolean = false,
    @SerializedName("is_live_attempt")
    val isLiveAttempt: Boolean = false,
    @SerializedName("workspace_status")
    val workspaceStatus: String? = null,
    @SerializedName("promotion_note")
    val promotionNote: String? = null,
    @SerializedName("promoted_at")
    val promotedAt: String? = null,
)

/**
 * Task from Orchestrator API (mobile app model - string IDs for compatibility)
 */
data class OrchestTask(
    val taskId: String,
    val title: String,
    val description: String? = null,
    val status: String,
    val projectId: String,
    val sessionId: String? = null,
    val sessionName: String? = null,
    val sessionStatus: String? = null,
    val hasActiveSession: Boolean = false,
    val isLiveAttempt: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
    val priority: Int = 0,
    val sequenceIndex: Int? = null,
    val sequenceTotal: Int? = null,
    val workspaceStatus: String? = null,
    val promotionNote: String? = null,
    val promotedAt: String? = null,
)

/**
 * Response wrapper for /api/v1/mobile/projects/{projectId}/tasks endpoint
 */
data class ProjectTasksResponse(
    @SerializedName("project_id")
    val projectId: Int,
    val tasks: List<OrchestTaskResponse>,
    @SerializedName("total")
    val total: Int
)

/**
 * API response wrapper for Orchestrator API
 */
data class OrchestratorApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null
)

/**
 * Response wrapper for project status endpoint
 */
data class ProjectStatusResponse(
    @SerializedName("project_id")
    val projectId: String = "",
    @SerializedName("project_name")
    val projectName: String = "",
    val description: String? = null,
    @SerializedName("project_rules")
    val projectRules: String? = null,
    @SerializedName("active_sessions")
    val activeSessions: Int = 0,
    @SerializedName("recent_sessions")
    val recentSessions: Int = 0,
    val tasks: TaskStatsResponse? = null,
    val sessions: List<ProjectSessionSummary> = emptyList()
)

data class ProjectSessionSummary(
    val id: Int = 0,
    val name: String = "",
    val status: String = "",
    @SerializedName("is_active")
    val isActive: Boolean = false,
    @SerializedName("started_at")
    val startedAt: String? = null
)

data class MobileSessionListItem(
    val id: Int = 0,
    val name: String = "",
    val status: String = "",
    @SerializedName("is_active")
    val isActive: Boolean = false,
    @SerializedName("project_id")
    val projectId: Int = 0,
    @SerializedName("started_at")
    val startedAt: String? = null,
    @SerializedName("stopped_at")
    val stoppedAt: String? = null,
)

data class MobileSessionsListResponse(
    val sessions: List<MobileSessionListItem> = emptyList()
)

data class ProjectBaselineOverview(
    val exists: Boolean = false,
    val path: String? = null,
    @SerializedName("file_count")
    val fileCount: Int = 0,
    @SerializedName("promoted_task_count")
    val promotedTaskCount: Int = 0,
)

data class ProjectTaskWorkspaceSummary(
    @SerializedName("task_id")
    val taskId: Int = 0,
    val title: String = "",
    @SerializedName("task_subfolder")
    val taskSubfolder: String = "",
    @SerializedName("workspace_status")
    val workspaceStatus: String? = null,
)

data class ProjectTreeResponse(
    @SerializedName("project_id")
    val projectId: String = "",
    @SerializedName("project_name")
    val projectName: String = "",
    val root: String = "",
    val exists: Boolean = false,
    @SerializedName("tree_lines")
    val treeLines: List<String> = emptyList(),
    @SerializedName("total_entries_shown")
    val totalEntriesShown: Int = 0,
    val truncated: Boolean = false,
    val baseline: ProjectBaselineOverview? = null,
    @SerializedName("task_workspaces")
    val taskWorkspaces: List<ProjectTaskWorkspaceSummary> = emptyList(),
)

data class ReviewDecision(
    @SerializedName("held_for_review")
    val heldForReview: Boolean = false,
    val policy: String? = null,
    val reason: String? = null,
    @SerializedName("changed_count")
    val changedCount: Int = 0,
    @SerializedName("warning_flags")
    val warningFlags: List<String> = emptyList(),
)

data class TaskChangeSetPayload(
    @SerializedName("schema")
    val schema: String? = null,
    @SerializedName("task_id")
    val taskId: Int? = null,
    @SerializedName("session_id")
    val sessionId: Int? = null,
    @SerializedName("task_execution_id")
    val taskExecutionId: Int? = null,
    val added: List<String> = emptyList(),
    val modified: List<String> = emptyList(),
    val deleted: List<String> = emptyList(),
    @SerializedName("changed_count")
    val changedCount: Int = 0,
    @SerializedName("added_count")
    val addedCount: Int = 0,
    @SerializedName("modified_count")
    val modifiedCount: Int = 0,
    @SerializedName("deleted_count")
    val deletedCount: Int = 0,
    @SerializedName("warning_flags")
    val warningFlags: List<String> = emptyList(),
    @SerializedName("verification_status")
    val verificationStatus: String? = null,
)

data class TaskChangeSetResponse(
    @SerializedName("task_id")
    val taskId: Int = 0,
    @SerializedName("task_execution_id")
    val taskExecutionId: Int? = null,
    @SerializedName("change_set")
    val changeSet: TaskChangeSetPayload? = null,
    @SerializedName("review_decision")
    val reviewDecision: ReviewDecision? = null,
    @SerializedName("recorded_at")
    val recordedAt: String? = null,
)

data class MobileActiveAlert(
    val level: String? = null,
    val message: String? = null,
    val at: String? = null
)

data class MobileSessionSummaryResponse(
    @SerializedName("session_id")
    val sessionId: Int = 0,
    val name: String = "",
    val status: String = "",
    @SerializedName("execution_mode")
    val executionMode: String = "automatic",
    @SerializedName("is_active")
    val isActive: Boolean = false,
    @SerializedName("started_at")
    val startedAt: String? = null,
    @SerializedName("active_alert")
    val activeAlert: MobileActiveAlert? = null,
    @SerializedName("task_progress")
    val taskProgress: TaskStatsResponse? = null,
    @SerializedName("recent_logs")
    val recentLogs: List<RecentActivity> = emptyList()
)

// ── Recovery Context ──────────────────────────────────────────

data class RecoveryTaskInfo(
    @SerializedName("task_id") val taskId: Int = 0,
    val title: String = "",
    val status: String = "",
    @SerializedName("repair_attempts") val repairAttempts: Int = 0,
    @SerializedName("files_changed") val filesChanged: List<String> = emptyList()
)

data class RecoveryPreservedState(
    @SerializedName("completed_tasks_checkpointed") val completedTasksCheckpointed: Boolean = false,
    @SerializedName("conversation_history_resumable") val conversationHistoryResumable: Boolean = false,
    @SerializedName("failed_task_rolled_back") val failedTaskRolledBack: Boolean = false,
    @SerializedName("remaining_plan_intact") val remainingPlanIntact: Boolean = false
)

data class RecoveryAction(
    val label: String = "",
    val action: String = "",
    @SerializedName("task_id") val taskId: Int? = null
)

data class MobileRecoveryContext(
    @SerializedName("session_id") val sessionId: Int = 0,
    @SerializedName("session_name") val sessionName: String = "",
    @SerializedName("session_status") val sessionStatus: String = "",
    @SerializedName("stop_reasons") val stopReasons: List<String> = emptyList(),
    @SerializedName("stop_category") val stopCategory: String = "",
    val tasks: List<RecoveryTaskInfo> = emptyList(),
    val preserved: RecoveryPreservedState = RecoveryPreservedState(),
    @SerializedName("recommended_actions") val recommendedActions: List<RecoveryAction> = emptyList()
)

// ── Narrative Timeline ────────────────────────────────────────

data class NarrativeTimelineEvent(
    val id: String = "",
    val at: String? = null,
    val phase: String = "",
    val kind: String = "",
    val title: String = "",
    val detail: String? = null
)

data class NarrativeTimelinePhase(
    val phase: String = "",
    val title: String = "",
    @SerializedName("event_count") val eventCount: Int = 0,
    val events: List<NarrativeTimelineEvent> = emptyList()
)

data class MobileNarrativeTimeline(
    @SerializedName("session_id") val sessionId: Int = 0,
    @SerializedName("session_status") val sessionStatus: String = "",
    val phases: List<NarrativeTimelinePhase> = emptyList(),
    @SerializedName("event_count") val eventCount: Int = 0,
    @SerializedName("source_note") val sourceNote: String = ""
)

data class MobileCheckpoint(
    val name: String = "",
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("step_index")
    val stepIndex: Int? = null,
    @SerializedName("completed_steps")
    val completedSteps: Int = 0,
    @SerializedName("checkpoint_type")
    val checkpointType: String? = null,
    val description: String? = null,
    val resumable: Boolean = true
)

data class MobileCheckpointListResponse(
    @SerializedName("session_id")
    val sessionId: Int = 0,
    @SerializedName("total_count")
    val totalCount: Int = 0,
    val checkpoints: List<MobileCheckpoint> = emptyList()
)

data class MobileSessionActionResponse(
    val status: String = "",
    @SerializedName("session_id")
    val sessionId: Int = 0,
    val message: String = ""
)

data class MobileTaskActionResponse(
    val status: String = "",
    @SerializedName("task_id")
    val taskId: Int = 0,
    @SerializedName("session_id")
    val sessionId: Int? = null,
    val message: String = ""
)

data class MobileTaskDetailResponse(
    val id: Int = 0,
    val title: String = "",
    val description: String? = null,
    val status: String = "pending",
    @SerializedName("project_id")
    val projectId: Int = 0,
    val priority: Int = 0,
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null,
    @SerializedName("error_message")
    val errorMessage: String? = null,
    @SerializedName("session_id")
    val sessionId: Int? = null,
    @SerializedName("session_name")
    val sessionName: String? = null,
    @SerializedName("has_active_session")
    val hasActiveSession: Boolean = false,
    @SerializedName("is_live_attempt")
    val isLiveAttempt: Boolean = false,
    @SerializedName("workspace_status")
    val workspaceStatus: String? = null,
    @SerializedName("promotion_note")
    val promotionNote: String? = null,
    @SerializedName("promoted_at")
    val promotedAt: String? = null,
)

data class InterventionRequest(
    val id: Int = 0,
    @SerializedName("session_id")
    val sessionId: Int = 0,
    @SerializedName("intervention_type")
    val interventionType: String = "guidance",
    val prompt: String = "",
    val status: String = "pending",
    val reply: String? = null,
    @SerializedName("initiated_by")
    val initiatedBy: String = "ai",
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("resolved_at")
    val resolvedAt: String? = null
)

data class InterventionListResponse(
    val interventions: List<InterventionRequest> = emptyList(),
    val total: Int = 0
)

data class ExecutionFailureSummaryResponse(
    @SerializedName("session_id")
    val sessionId: Int = 0,
    val summary: String = "",
    @SerializedName("operator_feedback")
    val operatorFeedback: String? = null
)

data class ReplanResponse(
    @SerializedName("new_session_id")
    val newSessionId: Int = 0,
    val message: String = ""
)

/**
 * Task statistics for project status response
 */
data class TaskStatsResponse(
    val total: Int = 0,
    val pending: Int = 0,
    val running: Int = 0,
    val done: Int = 0,
    val failed: Int = 0
)

data class KnowledgeUsageEntry(
    @SerializedName("knowledge_item_id")
    val knowledgeItemId: String = "",
    val title: String = "",
    @SerializedName("knowledge_type")
    val knowledgeType: String = "",
    @SerializedName("confidence_avg")
    val confidenceAvg: Double = 0.0,
    @SerializedName("confidence_max")
    val confidenceMax: Double = 0.0,
    @SerializedName("retrieval_reason")
    val retrievalReason: String = "",
    @SerializedName("used_in_prompt")
    val usedInPrompt: Boolean = false,
    @SerializedName("usage_count")
    val usageCount: Int = 1,
    @SerializedName("first_used_at")
    val firstUsedAt: String? = null,
    @SerializedName("last_used_at")
    val lastUsedAt: String? = null
)

data class KnowledgeUsageResponse(
    @SerializedName("session_id")
    val sessionId: Int = 0,
    val phases: Map<String, List<KnowledgeUsageEntry>> = emptyMap()
)
