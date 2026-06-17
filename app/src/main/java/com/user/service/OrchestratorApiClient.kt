package com.user.service

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.user.data.DashboardResponse
import com.user.data.PermissionActionResponse
import com.user.data.PermissionListResponse
import com.user.data.DashboardSummary
import com.user.data.RecentActivity
import com.user.data.OrchestTask
import com.user.data.OrchestTaskResponse
import com.user.data.MobileProjectsResponse
import com.user.data.MobileCheckpointListResponse
import com.user.data.MobileSessionListItem
import com.user.data.MobileSessionsListResponse
import com.user.data.MobileTaskDetailResponse
import com.user.data.MobileTaskActionResponse
import com.user.data.MobileSessionActionResponse
import com.user.data.ExecutionFailureSummaryResponse
import com.user.data.ReplanResponse
import com.user.data.InterventionListResponse
import com.user.data.InterventionRequest
import com.user.data.MobileNarrativeTimeline
import com.user.data.MobileRecoveryContext
import com.user.data.MobileSessionSummaryResponse
import com.user.data.OrchestratorApiResponse
import com.user.data.PrefsManager
import com.user.data.Project
import com.user.data.ProjectTreeResponse
import com.user.data.ProjectStatusResponse
import com.user.data.ProjectTasksResponse
import com.user.data.HumanGuidanceConflict
import com.user.data.HumanGuidanceConflictListResponse
import com.user.data.HumanGuidanceEntry
import com.user.data.HumanGuidanceListResponse
import com.user.data.HumanGuidanceReadiness
import com.user.data.HumanGuidanceRendered
import com.user.data.KnowledgeUsageResponse
import com.user.data.TaskChangeSetResponse
import com.user.data.previewSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Orchestrator API client for fetching project and task statistics
 *
 * This client handles connection failures gracefully - all errors are logged silently
 * and returned as failure Results. The UI should handle these failures without showing
 * error messages to the user, since local data is always available as a fallback.
 *
 * Required configuration:
 * - orchestratorServerUrl: Base URL of Orchestrator backend (e.g., http://xxx.xx.x.x:8080)
 * - orchestratorApiKey: API key from Orchestrator dashboard/admin settings
 * - gatewayToken: Same token used for OpenClaw Gateway authentication
 */
class OrchestratorApiClient(
    private val prefs: PrefsManager,
    private val gatewayToken: String,
    private val overrideServerUrl: String? = null,
    private val overrideApiKey: String? = null
) {
    private companion object {
        private const val TAG = "OrchestratorApiClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        // Add network interceptor to log all requests/responses for debugging
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            Log.d(TAG, "=== NETWORK REQUEST ===")
            Log.d(TAG, "URL: ${request.url}")
            Log.d(TAG, "Method: ${request.method}")
            Log.d(TAG, "Headers:")
            request.headers.forEach { (name, value) ->
                val displayValue = when (name.lowercase()) {
                    "x-openclaw-api-key", "authorization" -> previewSecret(value)
                    else -> value
                }
                Log.d(TAG, "  $name: $displayValue")
            }

            val response = chain.proceed(request)
            Log.d(TAG, "Response Status: ${response.code} ${response.message}")
            Log.d(TAG, "Response Headers:")
            response.headers.forEach { (name, value) ->
                Log.d(TAG, "  $name: $value")
            }
            Log.d(TAG, "========================")

            response
        }
        .build()

    private val gson = Gson()

    private fun getBaseUrl(): String {
        val rawBaseUrl = (overrideServerUrl ?: prefs.orchestratorServerUrl).trim().trimEnd('/')
        return when {
            rawBaseUrl.endsWith("/api/v1/mobile") -> rawBaseUrl.removeSuffix("/api/v1/mobile")
            rawBaseUrl.endsWith("/api/v1") -> rawBaseUrl.removeSuffix("/api/v1")
            rawBaseUrl.endsWith("/mobile") -> rawBaseUrl.removeSuffix("/mobile")
            else -> rawBaseUrl
        }
    }

    private fun buildMobileUrl(path: String): String {
        val normalizedPath = path.trimStart('/')
        return "${getBaseUrl()}/api/v1/mobile/$normalizedPath"
    }

    private fun buildApiUrl(path: String): String {
        val normalizedPath = path.trimStart('/')
        return "${getBaseUrl()}/api/v1/$normalizedPath"
    }

    private fun <T> buildFailure(message: String, exception: Exception? = null): Result<T> {
        val rootCause = exception?.message?.takeIf { it.isNotBlank() }
        val detail = when (exception) {
            is SocketTimeoutException -> "Request timed out. Check if Orchestrator is reachable."
            is IOException -> "Unable to reach Orchestrator. Check the server URL, network, and backend status."
            else -> rootCause
        }

        val fullMessage = if (detail != null && detail != message) {
            "$message $detail"
        } else {
            message
        }

        return Result.failure(Exception(fullMessage, exception))
    }

    private fun getHeaders(includeGatewayAuthorization: Boolean = false): Map<String, String> {
        val apiKey = overrideApiKey ?: prefs.orchestratorApiKey.ifBlank { gatewayToken }
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "X-OpenClaw-API-Key" to apiKey
        )
        if (includeGatewayAuthorization && gatewayToken.isNotBlank()) {
            headers["Authorization"] = "Bearer $gatewayToken"
        }
        return headers
    }

    /**
     * Fetch full dashboard payload including recent activity.
     */
    suspend fun getDashboard(): Result<DashboardResponse> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("dashboard")
            Log.d(TAG, "Fetching full dashboard payload from: $url")

            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Dashboard API failed: ${response.code} ${response.message}")
                    return@withContext buildFailure(
                        "Dashboard API failed (${response.code} ${response.message})."
                    )
                }

                val responseBody = response.body?.string() ?: throw Exception("Empty response")
                val apiResponse = gson.fromJson(responseBody, DashboardResponse::class.java)

                if (apiResponse.summary != null) {
                    Result.success(apiResponse)
                } else {
                    buildFailure("Dashboard response did not include summary data.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching full dashboard payload: ${e.message}")
            buildFailure("Failed to load Orchestrator dashboard.", e)
        }
    }

    /**
     * Fetch dashboard summary (project count, task stats, session stats)
     */
    suspend fun getDashboardSummary(): Result<DashboardSummary> = withContext(Dispatchers.IO) {
        getDashboard().fold(
            onSuccess = { dashboard ->
                Result.success(dashboard.summary!!)
            },
            onFailure = { error ->
                Result.failure(error)
            }
        )
    }

    /**
     * Fetch all projects
     */
    suspend fun getProjects(): Result<List<Project>> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("projects")
            Log.d(TAG, "Fetching projects from: $url")

            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Projects API failed: ${response.code} ${response.message}")
                    return@withContext buildFailure(
                        "Projects API failed (${response.code} ${response.message})."
                    )
                }

                val responseBody = response.body?.string() ?: throw Exception("Empty response")

                // Debug: log the raw response body to see what we got
                Log.d(TAG, "Projects API raw response: $responseBody")

                val mobileProjects = runCatching {
                    gson.fromJson(responseBody, MobileProjectsResponse::class.java)
                }.getOrNull()

                if (mobileProjects?.projects != null) {
                    Log.d(TAG, "Successfully fetched ${mobileProjects.projects.size} projects from mobile endpoint")
                    return@withContext Result.success(mobileProjects.projects)
                }

                val type = object : TypeToken<OrchestratorApiResponse<List<Project>>>() {}.type
                val apiResponse = gson.fromJson<OrchestratorApiResponse<List<Project>>>(responseBody, type)

                if (apiResponse.success && apiResponse.data != null) {
                    Log.d(TAG, "Successfully fetched ${apiResponse.data.size} projects")
                    Result.success(apiResponse.data)
                } else {
                    Log.w(TAG, "Projects API error: success=${apiResponse.success}, data=${apiResponse.data}, error=${apiResponse.error}")
                    buildFailure(apiResponse.error ?: "Projects response was missing usable data.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching projects: ${e.message}")
            buildFailure("Failed to load Orchestrator projects.", e)
        }
    }

    /**
     * Fetch tasks for a specific project
     */
    suspend fun getProjectTasks(projectId: String): Result<List<OrchestTask>> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("projects/${projectId}/tasks")
            Log.d(TAG, "Fetching tasks for project $projectId from: $url")

            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Tasks API failed for project $projectId: ${response.code} ${response.message}")
                    return@withContext buildFailure(
                        "Tasks API failed for project $projectId (${response.code} ${response.message})."
                    )
                }

                val responseBody = response.body?.string() ?: throw Exception("Empty response")

                // Parse the response - it's in format { "project_id": N, "tasks": [...], "total": N }
                val projectTasksResponse = gson.fromJson(responseBody, ProjectTasksResponse::class.java)

                // Convert from OrchestTaskResponse to OrchestTask
                val tasks = projectTasksResponse.tasks.map { it.toOrchestTask() }
                Log.d(TAG, "Successfully fetched ${tasks.size} tasks for project $projectId")
                Result.success(tasks)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching tasks for project $projectId: ${e.message}")
            buildFailure("Failed to load tasks for project $projectId.", e)
        }
    }

    /**
     * Extension function to convert OrchestTaskResponse to OrchestTask
     */
    private fun OrchestTaskResponse.toOrchestTask(): OrchestTask {
        return OrchestTask(
            taskId = this.id.toString(),
            title = this.title,
            description = this.description,
            status = this.status?.lowercase() ?: "unknown",
            projectId = this.projectId.toString(),
            sessionId = this.latestSessionId?.toString(),
            sessionName = this.latestSessionName,
            sessionStatus = this.latestSessionStatus,
            hasActiveSession = this.hasActiveSession,
            isLiveAttempt = this.isLiveAttempt,
            createdAt = this.createdAt,
            updatedAt = this.updatedAt ?: this.createdAt,
            priority = this.priority,
            sequenceIndex = this.sequenceIndex,
            sequenceTotal = this.sequenceTotal,
            workspaceStatus = this.workspaceStatus,
            promotionNote = this.promotionNote,
            promotedAt = this.promotedAt,
        )
    }

    /**
     * Get project status including task counts - called by mobile app for project progress display
     */
    suspend fun getProjectStatus(projectId: String): Result<ProjectStatusResponse> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("projects/${projectId}/status")
            Log.d(TAG, "Fetching status for project $projectId from: $url [project-status-v3]")

            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Project status API failed for $projectId: ${response.code} ${response.message}")
                    return@withContext buildFailure(
                        "Project status API failed for $projectId (${response.code} ${response.message})."
                    )
                }

                val responseBody = response.body?.string() ?: throw Exception("Empty response")
                Log.d(TAG, "Project status raw response for $projectId: $responseBody")
                val parsed = gson.fromJson(responseBody, ProjectStatusResponse::class.java)

                if (parsed.projectId.isBlank()) {
                    Log.w(TAG, "Unexpected response format for project $projectId - missing project_id")
                    return@withContext buildFailure("Project status response for $projectId was missing project_id.")
                }

                val normalized = parsed.copy(
                    projectId = parsed.projectId.ifBlank { projectId },
                    projectName = parsed.projectName.ifBlank { "Project" },
                    sessions = parsed.sessions.filter { it.id != 0 || it.name.isNotBlank() || it.status.isNotBlank() }
                )

                Log.d(TAG, "Successfully fetched status for project $projectId: ${normalized.tasks}")
                Result.success(normalized)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching status for project $projectId [project-status-v3]: ${e.message}")
            buildFailure("Failed to load project status for $projectId [project-status-v3].", e)
        }
    }

    /**
     * Fetch a compact file tree for a project.
     */
    suspend fun getProjectTree(projectId: String): Result<ProjectTreeResponse> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("projects/${projectId}/tree")
            Log.d(TAG, "Fetching file tree for project $projectId from: $url")

            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Project tree API failed for $projectId: ${response.code} ${response.message}")
                    return@withContext buildFailure(
                        "Project tree API failed for $projectId (${response.code} ${response.message})."
                    )
                }

                val responseBody = response.body?.string() ?: throw Exception("Empty response")
                val projectTree = gson.fromJson(responseBody, ProjectTreeResponse::class.java)
                Result.success(projectTree)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching file tree for project $projectId: ${e.message}")
            buildFailure("Failed to load file tree for project $projectId.", e)
        }
    }

    suspend fun getSessionSummary(sessionId: String): Result<MobileSessionSummaryResponse> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("sessions/${sessionId}/summary")
            Log.d(TAG, "Fetching session summary for $sessionId from: $url")

            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext buildFailure(
                        "Session summary API failed for $sessionId (${response.code} ${response.message})."
                    )
                }

                val responseBody = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(responseBody, MobileSessionSummaryResponse::class.java))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching session summary for $sessionId: ${e.message}")
            buildFailure("Failed to load session summary for $sessionId.", e)
        }
    }

    suspend fun getRecoveryContext(sessionId: String): Result<MobileRecoveryContext> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("sessions/${sessionId}/recovery-context")
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure(
                    "Recovery context API failed for $sessionId (${response.code} ${response.message})."
                )
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, MobileRecoveryContext::class.java))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching recovery context for $sessionId: ${e.message}")
            buildFailure("Failed to load recovery context for $sessionId.", e)
        }
    }

    suspend fun getNarrativeTimeline(sessionId: String): Result<MobileNarrativeTimeline> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("sessions/${sessionId}/timeline")
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure(
                    "Narrative timeline API failed for $sessionId (${response.code} ${response.message})."
                )
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, MobileNarrativeTimeline::class.java))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching narrative timeline for $sessionId: ${e.message}")
            buildFailure("Failed to load narrative timeline for $sessionId.", e)
        }
    }

    suspend fun listSessions(
        status: String? = null,
        projectId: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Result<List<MobileSessionListItem>> = withContext(Dispatchers.IO) {
        try {
            val path = buildString {
                append("sessions")
                val params = mutableListOf<String>()
                if (!status.isNullOrBlank()) params.add("status=$status")
                if (!projectId.isNullOrBlank()) params.add("project_id=$projectId")
                params.add("limit=$limit")
                params.add("offset=$offset")
                if (params.isNotEmpty()) {
                    append("?")
                    append(params.joinToString("&"))
                }
            }
            val url = buildMobileUrl(path)
            Log.d(TAG, "Fetching sessions from: $url")

            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext buildFailure(
                        "Sessions API failed (${response.code} ${response.message})."
                    )
                }

                val responseBody = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(responseBody, MobileSessionsListResponse::class.java).sessions)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching sessions: ${e.message}")
            buildFailure("Failed to load sessions.", e)
        }
    }

    suspend fun getSessionCheckpoints(sessionId: String): Result<MobileCheckpointListResponse> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("sessions/${sessionId}/checkpoints")
            Log.d(TAG, "Fetching session checkpoints for $sessionId from: $url")

            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray(includeGatewayAuthorization = false)))
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return@withContext Result.success(
                        MobileCheckpointListResponse(
                            sessionId = sessionId.toIntOrNull() ?: 0,
                            totalCount = 0,
                            checkpoints = emptyList()
                        )
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext buildFailure(
                        "Checkpoint API failed for $sessionId (${response.code} ${response.message})."
                    )
                }

                val responseBody = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(responseBody, MobileCheckpointListResponse::class.java))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching checkpoints for $sessionId: ${e.message}")
            buildFailure("Failed to load checkpoints for $sessionId.", e)
        }
    }

    suspend fun stopSession(sessionId: String): Result<MobileSessionActionResponse> = withContext(Dispatchers.IO) {
        try {
            postSessionActionWithFallback(
                sessionId = sessionId,
                action = "stop",
                defaultMessage = "Session stop requested"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping session $sessionId: ${e.message}")
            buildFailure("Failed to stop session $sessionId.", e)
        }
    }

    suspend fun resumeSession(sessionId: String): Result<MobileSessionActionResponse> = withContext(Dispatchers.IO) {
        try {
            postSessionActionWithFallback(
                sessionId = sessionId,
                action = "resume",
                defaultMessage = "Session resume requested"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error resuming session $sessionId: ${e.message}")
            buildFailure("Failed to resume session $sessionId.", e)
        }
    }

    private fun postSessionActionWithFallback(
        sessionId: String,
        action: String,
        defaultMessage: String
    ): Result<MobileSessionActionResponse> {
        val actionLabel = action.replaceFirstChar { it.uppercase() }
        val attempts = buildSessionActionAttempts(sessionId, action)
        var lastError: String? = null
        var lastStatus = ""

        attempts.forEachIndexed { index, url ->
            if (index == 0) {
                Log.d(TAG, "$actionLabel session $sessionId via: $url")
            } else {
                Log.d(TAG, "Retrying $action session $sessionId via: $url")
            }

            val request = buildEmptyPostRequest(url)
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string().orEmpty()
                    return Result.success(parseSessionActionResponse(responseBody, sessionId, action, defaultMessage))
                }

                lastStatus = "${response.code} ${response.message}"
                lastError = readApiError(response.body?.string().orEmpty()) ?: lastError
                Log.d(TAG, "$actionLabel session endpoint failed for session $sessionId ($lastStatus)")
            }
        }

        return buildFailure(
            lastError ?: "$actionLabel session API failed for $sessionId ($lastStatus)."
        )
    }

    private fun buildSessionActionAttempts(sessionId: String, action: String): List<String> {
        val mobilePath = "sessions/${sessionId}/$action"
        return if (action == "stop") {
            listOf(
                buildMobileUrl("$mobilePath?force=true"),
                buildMobileUrl(mobilePath)
            )
        } else {
            listOf(
                buildMobileUrl(mobilePath)
            )
        }
    }

    private fun buildEmptyPostRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
    }

    private fun parseSessionActionResponse(
        responseBody: String,
        sessionId: String,
        action: String,
        defaultMessage: String
    ): MobileSessionActionResponse {
        if (responseBody.isBlank()) {
            return MobileSessionActionResponse(
                status = action,
                sessionId = sessionId.toIntOrNull() ?: 0,
                message = defaultMessage
            )
        }
        return gson.fromJson(responseBody, MobileSessionActionResponse::class.java)
    }

    private fun readApiError(errorBody: String): String? {
        if (errorBody.isBlank()) return null
        return runCatching {
            val json = gson.fromJson(errorBody, com.google.gson.JsonObject::class.java)
            json?.get("detail")?.asString
                ?: json?.get("error")?.asString
                ?: json?.get("message")?.asString
        }.getOrNull() ?: errorBody.take(300)
    }

    suspend fun retryTask(taskId: String): Result<MobileTaskActionResponse> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("tasks/${taskId}/retry")
            Log.d(TAG, "Retrying task $taskId via: $url")

            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray(includeGatewayAuthorization = false)))
                .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    val fallbackUrl = buildApiUrl("tasks/${taskId}/retry")
                    Log.d(TAG, "Mobile retry endpoint missing for task $taskId; trying: $fallbackUrl")
                    val fallbackRequest = Request.Builder()
                        .url(fallbackUrl)
                        .headers(okhttp3.Headers.headersOf(*buildHeadersArray(includeGatewayAuthorization = false)))
                        .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                        .build()

                    client.newCall(fallbackRequest).execute().use { fallbackResponse ->
                        if (!fallbackResponse.isSuccessful) {
                            return@withContext buildFailure(
                                "Retry task API failed for $taskId (${fallbackResponse.code} ${fallbackResponse.message})."
                            )
                        }

                        val fallbackBody = fallbackResponse.body?.string() ?: throw Exception("Empty response")
                        return@withContext Result.success(gson.fromJson(fallbackBody, MobileTaskActionResponse::class.java))
                    }
                }
                if (!response.isSuccessful) {
                    return@withContext buildFailure(
                        "Retry task API failed for $taskId (${response.code} ${response.message})."
                    )
                }

                val responseBody = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(responseBody, MobileTaskActionResponse::class.java))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error retrying task $taskId: ${e.message}")
            buildFailure("Failed to retry task $taskId.", e)
        }
    }

    suspend fun getTaskDetail(taskId: String): Result<MobileTaskDetailResponse> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("tasks/${taskId}")
            Log.d(TAG, "Fetching task detail for $taskId from: $url")

            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext buildFailure(
                        "Task detail API failed for $taskId (${response.code} ${response.message})."
                    )
                }

                val responseBody = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(responseBody, MobileTaskDetailResponse::class.java))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching task detail for $taskId: ${e.message}")
            buildFailure("Failed to load task detail for $taskId.", e)
        }
    }

    /**
     * Fetch tasks filtered by status
     */
    @Suppress("unused")
    suspend fun getTasksByStatus(projectId: String, status: String): Result<List<OrchestTask>> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("projects/${projectId}/tasks?status=$status")
            Log.d(TAG, "Fetching filtered tasks for project $projectId with status $status")

            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Tasks API failed for project $projectId (status=$status): ${response.code} ${response.message}")
                    return@withContext buildFailure(
                        "Filtered tasks API failed for project $projectId (${response.code} ${response.message})."
                    )
                }

                val responseBody = response.body?.string() ?: throw Exception("Empty response")

                // Parse the response - it's in format { "project_id": N, "tasks": [...], "total": N }
                val projectTasksResponse = gson.fromJson(responseBody, ProjectTasksResponse::class.java)

                // Convert from OrchestTaskResponse to OrchestTask and filter by status
                val tasks = projectTasksResponse.tasks
                    .map { it.toOrchestTask() }
                    .filter { it.status == status.lowercase() }
                Log.d(TAG, "Successfully fetched ${tasks.size} tasks for project $projectId (status=$status)")
                Result.success(tasks)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching filtered tasks for project $projectId: ${e.message}")
            buildFailure("Failed to load filtered tasks for project $projectId.", e)
        }
    }

    // ── US2: Session Start / Pause ────────────────────────────────

    suspend fun startSession(projectId: Int, name: String, taskId: Int? = null): Result<MobileSessionActionResponse> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("sessions")
            val jsonBody = if (taskId != null) {
                "{\"project_id\":$projectId,\"name\":\"${name.replace("\"", "\\\"")}\",\"task_id\":$taskId}"
            } else {
                "{\"project_id\":$projectId,\"name\":\"${name.replace("\"", "\\\"")}\"}"
            }
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Start session failed (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, MobileSessionActionResponse::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to start session.", e)
        }
    }

    suspend fun pauseSession(sessionId: String): Result<MobileSessionActionResponse> = withContext(Dispatchers.IO) {
        try {
            postSessionActionWithFallback(
                sessionId = sessionId,
                action = "pause",
                defaultMessage = "Session pause requested"
            )
        } catch (e: Exception) {
            buildFailure("Failed to pause session $sessionId.", e)
        }
    }

    // ── US3: Task Position / Workspace Review ─────────────────────

    suspend fun updateTaskPosition(taskId: String, planPosition: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("tasks/${taskId}/position")
            val jsonBody = "{\"plan_position\":$planPosition}"
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .patch(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Update position failed (${response.code}).")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            buildFailure("Failed to update task position.", e)
        }
    }

    suspend fun submitWorkspaceReview(taskId: String, action: String, note: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("tasks/${taskId}/review")
            val noteJson = if (note != null) ",\"note\":\"${note.replace("\"", "\\\"")}\"" else ""
            val jsonBody = "{\"action\":\"$action\"$noteJson}"
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Submit review failed (${response.code}).")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            buildFailure("Failed to submit workspace review.", e)
        }
    }

    // ── US4: Checkpoint Load / Delete ─────────────────────────────

    suspend fun loadCheckpoint(sessionId: String, checkpointName: String): Result<MobileSessionActionResponse> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("sessions/${sessionId}/checkpoint/load")
            val jsonBody = "{\"checkpoint_name\":\"${checkpointName.replace("\"", "\\\"")}\"}"
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Load checkpoint failed (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, MobileSessionActionResponse::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to load checkpoint.", e)
        }
    }

    suspend fun deleteCheckpoint(sessionId: String, checkpointName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("sessions/${sessionId}/checkpoints/${checkpointName}")
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .delete()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Delete checkpoint failed (${response.code}).")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            buildFailure("Failed to delete checkpoint.", e)
        }
    }

    // ── US5: Permissions ──────────────────────────────────────────

    suspend fun listPermissions(status: String? = null): Result<PermissionListResponse> = withContext(Dispatchers.IO) {
        try {
            val path = if (!status.isNullOrBlank()) "permissions?status=$status" else "permissions"
            val url = buildMobileUrl(path)
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("List permissions failed (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, PermissionListResponse::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to list permissions.", e)
        }
    }

    suspend fun approvePermission(requestId: Int, autoApproveSame: Boolean = false): Result<PermissionActionResponse> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("permissions/${requestId}/approve")
            val jsonBody = "{\"auto_approve_same\":$autoApproveSame}"
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Approve permission failed (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, PermissionActionResponse::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to approve permission.", e)
        }
    }

    suspend fun rejectPermission(requestId: Int): Result<PermissionActionResponse> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("permissions/${requestId}/reject")
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Reject permission failed (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, PermissionActionResponse::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to reject permission.", e)
        }
    }

    // ── Interventions ─────────────────────────────────────────────

    suspend fun listInterventions(sessionId: String, status: String? = null): Result<InterventionListResponse> = withContext(Dispatchers.IO) {
        try {
            val path = buildString {
                append("sessions/$sessionId/interventions")
                if (!status.isNullOrBlank()) append("?status=$status")
            }
            val request = Request.Builder()
                .url(buildMobileUrl(path))
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("List interventions failed (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, InterventionListResponse::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to list interventions for session $sessionId.", e)
        }
    }

    suspend fun replyToIntervention(sessionId: String, interventionId: Int, reply: String): Result<InterventionRequest> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("sessions/$sessionId/interventions/$interventionId/reply")
            val jsonBody = "{\"reply\":\"${reply.replace("\"", "\\\"")}\"}"
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Reply to intervention failed (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, InterventionRequest::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to reply to intervention $interventionId.", e)
        }
    }

    suspend fun approveIntervention(sessionId: String, interventionId: Int): Result<InterventionRequest> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("sessions/$sessionId/interventions/$interventionId/approve")
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Approve intervention failed (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, InterventionRequest::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to approve intervention $interventionId.", e)
        }
    }

    suspend fun denyIntervention(sessionId: String, interventionId: Int, reason: String? = null): Result<InterventionRequest> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("sessions/$sessionId/interventions/$interventionId/deny")
            val jsonBody = if (reason != null) "{\"reason\":\"${reason.replace("\"", "\\\"")}\"}" else "{}"
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Deny intervention failed (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, InterventionRequest::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to deny intervention $interventionId.", e)
        }
    }

    // ── Replan Flow ───────────────────────────────────────────────

    suspend fun getFailureSummary(sessionId: String): Result<ExecutionFailureSummaryResponse> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("sessions/$sessionId/failure-summary")
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Get failure summary failed (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, ExecutionFailureSummaryResponse::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to load failure summary for session $sessionId.", e)
        }
    }

    suspend fun submitOperatorFeedback(sessionId: String, feedback: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("sessions/$sessionId/operator-feedback")
            val jsonBody = "{\"feedback\":\"${feedback.replace("\"", "\\\"")}\"}"
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Submit operator feedback failed (${response.code}).")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            buildFailure("Failed to submit operator feedback for session $sessionId.", e)
        }
    }

    suspend fun triggerReplan(sessionId: String): Result<ReplanResponse> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("sessions/$sessionId/replan")
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Trigger replan failed (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, ReplanResponse::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to trigger replan for session $sessionId.", e)
        }
    }

    suspend fun getKnowledgeUsage(sessionId: String): Result<KnowledgeUsageResponse> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("sessions/$sessionId/knowledge-usage")
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Get knowledge usage failed (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, KnowledgeUsageResponse::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to load knowledge usage for session $sessionId.", e)
        }
    }

    // ── Phase 9H: Workspace Change Governance ─────────────────────

    suspend fun getLatestTaskChangeSet(taskId: String): Result<TaskChangeSetResponse> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("tasks/$taskId/change-set")
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Get change set failed (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, TaskChangeSetResponse::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to load change set for task $taskId.", e)
        }
    }

    suspend fun rejectTaskChangeSet(taskId: String, taskExecutionId: Int, note: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("tasks/$taskId/change-set/reject")
            val noteJson = if (note != null) ",\"note\":\"${note.replace("\"", "\\\"")}\"" else ""
            val jsonBody = "{\"task_execution_id\":$taskExecutionId$noteJson}"
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Reject change set failed (${response.code}).")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            buildFailure("Failed to reject change set for task $taskId.", e)
        }
    }

    // ── Human Guidance ────────────────────────────────────────────

    suspend fun getGuidanceReadiness(projectId: Int): Result<HumanGuidanceReadiness> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("projects/$projectId/guidance/readiness")
            val request = Request.Builder().url(url).headers(okhttp3.Headers.headersOf(*buildHeadersArray())).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Guidance readiness API failed for $projectId (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, HumanGuidanceReadiness::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to load guidance readiness for $projectId.", e)
        }
    }

    suspend fun listGuidance(projectId: Int, status: String? = "active", limit: Int = 50): Result<HumanGuidanceListResponse> = withContext(Dispatchers.IO) {
        try {
            val params = buildString {
                val p = mutableListOf("limit=$limit")
                if (!status.isNullOrBlank() && status != "all") p.add("status=$status")
                append("?${p.joinToString("&")}")
            }
            val url = buildMobileUrl("projects/$projectId/guidance$params")
            val request = Request.Builder().url(url).headers(okhttp3.Headers.headersOf(*buildHeadersArray())).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("List guidance API failed for $projectId (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, HumanGuidanceListResponse::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to list guidance for $projectId.", e)
        }
    }

    suspend fun createGuidance(projectId: Int, payload: Map<String, Any>): Result<HumanGuidanceEntry> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("projects/$projectId/guidance")
            val jsonBody = gson.toJson(payload)
            val request = Request.Builder().url(url).headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty()
                    return@withContext buildFailure(readApiError(errBody) ?: "Create guidance failed (${response.code}).")
                }
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, HumanGuidanceEntry::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to create guidance.", e)
        }
    }

    suspend fun patchGuidance(guidanceId: Int, payload: Map<String, Any>): Result<HumanGuidanceEntry> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("guidance/$guidanceId")
            val jsonBody = gson.toJson(payload)
            val request = Request.Builder().url(url).headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .patch(jsonBody.toRequestBody("application/json".toMediaTypeOrNull())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Patch guidance $guidanceId failed (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, HumanGuidanceEntry::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to patch guidance $guidanceId.", e)
        }
    }

    suspend fun archiveGuidance(guidanceId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("guidance/$guidanceId")
            val request = Request.Builder().url(url).headers(okhttp3.Headers.headersOf(*buildHeadersArray())).delete().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Archive guidance $guidanceId failed (${response.code}).")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            buildFailure("Failed to archive guidance $guidanceId.", e)
        }
    }

    suspend fun getRenderedGuidance(projectId: Int, backend: String? = null, modelFamily: String? = null, purpose: String? = null): Result<HumanGuidanceRendered> = withContext(Dispatchers.IO) {
        try {
            val params = mutableListOf<String>()
            if (!backend.isNullOrBlank()) params.add("backend=$backend")
            if (!modelFamily.isNullOrBlank()) params.add("model_family=$modelFamily")
            if (!purpose.isNullOrBlank()) params.add("purpose=$purpose")
            val query = if (params.isEmpty()) "" else "?${params.joinToString("&")}"
            val url = buildMobileUrl("projects/$projectId/guidance/rendered$query")
            val request = Request.Builder().url(url).headers(okhttp3.Headers.headersOf(*buildHeadersArray())).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Get rendered guidance failed for $projectId (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, HumanGuidanceRendered::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to get rendered guidance for $projectId.", e)
        }
    }

    suspend fun listGuidanceConflicts(projectId: Int, status: String? = "open"): Result<HumanGuidanceConflictListResponse> = withContext(Dispatchers.IO) {
        try {
            val query = if (!status.isNullOrBlank()) "?status=$status" else ""
            val url = buildMobileUrl("projects/$projectId/guidance/conflicts$query")
            val request = Request.Builder().url(url).headers(okhttp3.Headers.headersOf(*buildHeadersArray())).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("List guidance conflicts failed for $projectId (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, HumanGuidanceConflictListResponse::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to list guidance conflicts for $projectId.", e)
        }
    }

    suspend fun patchGuidanceConflict(projectId: Int, conflictId: Int, payload: Map<String, Any>): Result<HumanGuidanceConflict> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("projects/$projectId/guidance/conflicts/$conflictId")
            val jsonBody = gson.toJson(payload)
            val request = Request.Builder().url(url).headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .patch(jsonBody.toRequestBody("application/json".toMediaTypeOrNull())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Patch guidance conflict $conflictId failed (${response.code}).")
                val json = response.body?.string() ?: throw Exception("Empty response")
                Result.success(gson.fromJson(json, HumanGuidanceConflict::class.java))
            }
        } catch (e: Exception) {
            buildFailure("Failed to patch guidance conflict $conflictId.", e)
        }
    }

    suspend fun patchGuidanceActivation(projectId: Int, payload: Map<String, Any>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("projects/$projectId/guidance/activation")
            val jsonBody = gson.toJson(payload)
            val request = Request.Builder().url(url).headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .patch(jsonBody.toRequestBody("application/json".toMediaTypeOrNull())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Patch guidance activation failed for $projectId (${response.code}).")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            buildFailure("Failed to patch guidance activation for $projectId.", e)
        }
    }

    suspend fun disableGuidanceActivation(projectId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("projects/$projectId/guidance/activation/disable")
            val request = Request.Builder().url(url).headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .post(okhttp3.RequestBody.create(null, ByteArray(0))).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext buildFailure("Disable guidance activation failed for $projectId (${response.code}).")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            buildFailure("Failed to disable guidance activation for $projectId.", e)
        }
    }

    private fun buildHeadersArray(includeGatewayAuthorization: Boolean = false): Array<String> {
        val headers = getHeaders(includeGatewayAuthorization)
        return headers.flatMap { (key, value) -> listOf(key, value) }.toTypedArray()
    }

    /**
     * Test connection to Orchestrator API
     */
    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = buildMobileUrl("dashboard")
            Log.d(TAG, "Testing connection to Orchestrator: $url")

            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(*buildHeadersArray()))
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                Log.d(TAG, "Connection test result: ${if (success) "SUCCESS" else "FAILED (${response.code})"}")
                Result.success(success)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connection test failed: ${e.message}")
            buildFailure("Orchestrator connection test failed.", e)
        }
    }
}
