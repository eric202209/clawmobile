package com.user.data

import android.content.Context
import android.util.Log
import com.user.BuildConfig

private const val PREFS_TAG = "PrefsManager"

fun previewSecret(value: String): String =
    when {
        value.isEmpty() -> "(empty)"
        value.length <= 8 -> "${"*".repeat(value.length)} (${value.length} chars)"
        else -> "${value.take(8)}...${value.length} chars"
    }

class PrefsManager(context: Context) {
    private val prefs = context.getSharedPreferences("openclaw_prefs", Context.MODE_PRIVATE)
    private companion object {
        const val KEY_PINNED_PROJECT_IDS = "pinned_project_ids"
        const val KEY_PINNED_SESSION_IDS = "pinned_session_ids"
        const val KEY_RECENT_INPUT_HISTORY = "recent_input_history"
        const val MAX_RECENT_INPUTS = 24
    }

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean("onboarding_completed", false)
        set(value) = prefs.edit().putBoolean("onboarding_completed", value).apply()

    // The default comes from local.properties
    var serverUrl: String
        get() = prefs.getString("server_url", BuildConfig.DEFAULT_SERVER_URL)
            ?: BuildConfig.DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString("server_url", value).apply()

    // OpenClaw Gateway token
    var gatewayToken: String
        get() = prefs.getString("gateway_token", "") ?: ""
        set(value) = prefs.edit().putString("gateway_token", value).apply()

    // Device ID for Ed25519 pairing (auto-generated once)
    var deviceId: String
        get() = prefs.getString("device_id", "") ?: ""
        set(value) = prefs.edit().putString("device_id", value).apply()

    // ── GitHub Integration ───────────────────────────────────
    var githubToken: String
        get() = prefs.getString("github_token", "") ?: ""
        set(value) = prefs.edit().putString("github_token", value).apply()

    var githubApiUrl: String
        get() = prefs.getString("github_api_url", "https://api.github.com") ?: "https://api.github.com"
        set(value) = prefs.edit().putString("github_api_url", value).apply()

    var githubDefaultRepo: String
        get() = prefs.getString("github_default_repo", "") ?: ""
        set(value) = prefs.edit().putString("github_default_repo", value).apply()

    // ── API Client Environments ───────────────────────────────
    var currentEnvironment: String
        get() = prefs.getString("api_current_env", "default") ?: "default"
        set(value) = prefs.edit().putString("api_current_env", value).apply()

    // ── Orchestrator Integration ──────────────────────────────
    var orchestratorServerUrl: String
        get() {
            val value = prefs.getString("orchestrator_server_url", "") ?: ""
            Log.d(PREFS_TAG, "orchestratorServerUrl GET: $value")
            return value
        }
        set(value) {
            Log.d(PREFS_TAG, "orchestratorServerUrl SET: $value")
            prefs.edit().putString("orchestrator_server_url", value).apply()
        }

    var orchestratorApiKey: String
        get() {
            val value = prefs.getString("orchestrator_api_key", "") ?: ""
            // Don't log the full API key for security, just show if it's set
            Log.d(PREFS_TAG, "orchestratorApiKey GET: ${previewSecret(value)}")
            return value
        }
        set(value) {
            Log.d(PREFS_TAG, "orchestratorApiKey SET: ${previewSecret(value)}")
            prefs.edit().putString("orchestrator_api_key", value).apply()
        }

    // Check if Orchestrator is configured
    fun isOrchestratorConfigured(): Boolean {
        return orchestratorServerUrl.isNotEmpty() &&
            (orchestratorApiKey.isNotEmpty() || gatewayToken.isNotEmpty())
    }

    fun getPinnedProjectIds(): Set<String> =
        prefs.getStringSet(KEY_PINNED_PROJECT_IDS, emptySet()) ?: emptySet()

    fun isProjectPinned(projectId: String): Boolean = getPinnedProjectIds().contains(projectId)

    fun togglePinnedProject(projectId: String): Boolean {
        val updated = getPinnedProjectIds().toMutableSet()
        val pinned = if (updated.contains(projectId)) {
            updated.remove(projectId)
            false
        } else {
            updated.add(projectId)
            true
        }
        prefs.edit().putStringSet(KEY_PINNED_PROJECT_IDS, updated).apply()
        return pinned
    }

    fun getPinnedSessionIds(): Set<String> =
        prefs.getStringSet(KEY_PINNED_SESSION_IDS, emptySet()) ?: emptySet()

    fun isSessionPinned(sessionId: String): Boolean = getPinnedSessionIds().contains(sessionId)

    fun togglePinnedSession(sessionId: String): Boolean {
        val updated = getPinnedSessionIds().toMutableSet()
        val pinned = if (updated.contains(sessionId)) {
            updated.remove(sessionId)
            false
        } else {
            updated.add(sessionId)
            true
        }
        prefs.edit().putStringSet(KEY_PINNED_SESSION_IDS, updated).apply()
        return pinned
    }

    fun getRecentInputHistory(): List<String> {
        val raw = prefs.getString(KEY_RECENT_INPUT_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun saveRecentInputHistory(history: List<String>) {
        val trimmed = history
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeLast(MAX_RECENT_INPUTS)
        prefs.edit().putString(KEY_RECENT_INPUT_HISTORY, trimmed.joinToString("\n")).apply()
    }

    // ── Intervention Polling ──────────────────────────────────
    var lastKnownInterventionSessionIds: Set<Int>
        get() = (prefs.getStringSet("last_known_intervention_session_ids", emptySet()) ?: emptySet())
            .mapNotNull { it.toIntOrNull() }.toSet()
        set(value) = prefs.edit().putStringSet("last_known_intervention_session_ids", value.map { it.toString() }.toSet()).apply()

    // ── Permission Polling (US5) ──────────────────────────────
    var lastKnownPermissionIds: Set<Int>
        get() = (prefs.getStringSet("last_known_permission_ids", emptySet()) ?: emptySet())
            .mapNotNull { it.toIntOrNull() }.toSet()
        set(value) = prefs.edit().putStringSet("last_known_permission_ids", value.map { it.toString() }.toSet()).apply()
}
