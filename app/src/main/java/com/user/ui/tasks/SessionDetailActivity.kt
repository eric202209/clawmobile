package com.user.ui.tasks

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.user.ClawMobileApplication
import com.user.R
import com.user.data.ExecutionFailureSummaryResponse
import com.user.data.InterventionRequest
import com.user.data.MobileCheckpointListResponse
import com.user.data.MobileSessionSummaryResponse
import com.user.data.RecentActivity
import com.user.databinding.ActivitySessionDetailBinding
import com.user.service.LogEntry
import com.user.service.OrchestratorApiClient
import com.user.service.WebSocketManager
import com.user.ui.FailureSummary
import com.user.ui.OutputHighlighter
import com.user.ui.TimeFormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SessionDetailActivity : AppCompatActivity() {

    private enum class LogFilter {
        ALL,
        ERRORS,
        PHASES,
        CHECKPOINTS
    }

    private lateinit var binding: ActivitySessionDetailBinding
    private var orchestratorClient: OrchestratorApiClient? = null
    private var webSocketManager: WebSocketManager? = null
    private var sessionId: String = ""
    private var sessionName: String = "Session"
    private var currentLogFilter: LogFilter = LogFilter.ALL
    private var latestLogs: List<RecentActivity> = emptyList()
    private var wsLogEntries: MutableList<LogEntry> = mutableListOf()
    private var latestSummary: MobileSessionSummaryResponse? = null
    private var latestCheckpoints: MobileCheckpointListResponse? = null
    private var currentIntervention: InterventionRequest? = null
    private var isWebSocketOffline = false
    private var latestFailureSummary: ExecutionFailureSummaryResponse? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionId = intent.getStringExtra("session_id") ?: ""
        sessionName = intent.getStringExtra("session_name") ?: "Session"

        val app = application as ClawMobileApplication
        if (app.prefsManager.isOrchestratorConfigured()) {
            orchestratorClient = OrchestratorApiClient(
                prefs = app.prefsManager,
                gatewayToken = app.prefsManager.gatewayToken
            )
            webSocketManager = WebSocketManager(app.prefsManager).also { wsm ->
                wsm.onReconnecting = { attempt ->
                    runOnUiThread { showLiveStatus("Reconnecting ($attempt/5)...", R.color.status_pending) }
                }
                wsm.onMaxAttemptsReached = {
                    runOnUiThread {
                        isWebSocketOffline = true
                        showLiveStatus("Offline — last 10 logs shown", R.color.status_failed)
                        loadSessionData(showToast = false)
                    }
                }
            }
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = sessionName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.refreshButton.setOnClickListener { loadSessionData(showToast = true) }
        binding.pauseButton.setOnClickListener { pauseSession() }
        binding.resumeButton.setOnClickListener { resumeSession() }
        binding.stopButton.setOnClickListener { stopSession() }
        binding.checkpointsButton.setOnClickListener { openCheckpointsSheet() }
        binding.logFilterAllButton.setOnClickListener { setLogFilter(LogFilter.ALL) }
        binding.logFilterErrorsButton.setOnClickListener { setLogFilter(LogFilter.ERRORS) }
        binding.logFilterOrchestrationButton.setOnClickListener { setLogFilter(LogFilter.PHASES) }
        binding.logFilterCheckpointButton.setOnClickListener { setLogFilter(LogFilter.CHECKPOINTS) }
        setLogFilter(LogFilter.ALL)

        binding.interventionApproveButton.setOnClickListener { handleApproveIntervention() }
        binding.interventionDenyButton.setOnClickListener { handleDenyIntervention() }
        binding.interventionSubmitButton.setOnClickListener { handleSubmitGuidance() }
        binding.sendToProjectArchitectButton.setOnClickListener { handleSendToProjectArchitect() }

        loadSessionData(showToast = false)
    }

    override fun onStart() {
        super.onStart()
        webSocketManager?.let { wsm ->
            wsLogEntries.clear()
            isWebSocketOffline = false
            wsm.connect(sessionId)
            showLiveStatus("Live", R.color.status_running)
            lifecycleScope.launch {
                wsm.logStream.collectLatest { entry ->
                    wsLogEntries.add(0, entry)
                    if (wsLogEntries.size > 50) wsLogEntries.removeLastOrNull()
                    runOnUiThread { renderRecentLogs() }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        webSocketManager?.disconnect()
        binding.liveStatusBanner.visibility = View.GONE
    }

    private fun showLiveStatus(text: String, colorRes: Int) {
        binding.liveStatusBanner.visibility = View.VISIBLE
        binding.liveStatusBanner.text = text
        binding.liveStatusBanner.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun pauseSession() {
        val client = orchestratorClient ?: return
        CoroutineScope(Dispatchers.Main).launch {
            client.pauseSession(sessionId).onSuccess {
                Snackbar.make(binding.root, it.message.ifBlank { "Session paused" }, Snackbar.LENGTH_SHORT).show()
                loadSessionData(showToast = false)
            }.onFailure { error ->
                Snackbar.make(binding.root, error.message ?: "Failed to pause session", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun openCheckpointsSheet() {
        val sheet = CheckpointsBottomSheet.newInstance(sessionId)
        sheet.show(supportFragmentManager, "checkpoints")
    }

    private fun loadSessionData(showToast: Boolean) {
        val client = orchestratorClient ?: run {
            binding.sessionSummary.text = "Orchestrator is not configured on this device."
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            client.getSessionSummary(sessionId).onSuccess { summary ->
                latestSummary = summary
                bindSummary(summary)
                renderRecoveryCard()
                if (showToast) {
                    Snackbar.make(binding.root, "Session refreshed", Snackbar.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                binding.sessionSummary.text = error.message ?: "Unable to load session summary."
            }

            client.getSessionCheckpoints(sessionId).onSuccess { checkpoints ->
                latestCheckpoints = checkpoints
                bindCheckpoints(checkpoints)
                renderRecoveryCard()
            }.onFailure { error ->
                binding.checkpointSummaryView.text = error.message ?: "Unable to load checkpoints."
                binding.checkpointListView.text = ""
                latestCheckpoints = null
                renderRecoveryCard()
            }

            client.listInterventions(sessionId, status = "pending").onSuccess { response ->
                currentIntervention = response.interventions.firstOrNull()
                bindIntervention(currentIntervention)
            }.onFailure {
                currentIntervention = null
                bindIntervention(null)
            }

            val isStopped = latestSummary?.status?.equals("stopped", ignoreCase = true) == true
            if (isStopped) {
                client.getFailureSummary(sessionId).onSuccess { fs ->
                    latestFailureSummary = fs
                    bindReplanCard(fs)
                }.onFailure {
                    bindReplanCard(null)
                }
            } else {
                latestFailureSummary = null
                binding.replanCard.visibility = View.GONE
            }

        }
    }

    private fun bindSummary(summary: MobileSessionSummaryResponse) {
        val statusLabel = when (summary.status.lowercase()) {
            "awaiting_input" -> "WAITING"
            else -> summary.status.uppercase()
        }
        binding.sessionStatusBadge.text = statusLabel
        val badgeRes = when (summary.status.lowercase()) {
            "running" -> R.drawable.badge_running
            "paused", "awaiting_input" -> R.drawable.badge_pending
            "stopped" -> R.drawable.badge_timeout
            "completed" -> R.drawable.badge_completed
            else -> R.drawable.badge_pending
        }
        binding.sessionStatusBadge.setBackgroundResource(badgeRes)

        val progress = summary.taskProgress
        binding.sessionSummary.text = when {
            progress == null -> "No task progress available yet"
            progress.failed > 0 -> "${progress.failed} failed • ${progress.running} running • ${progress.done} done"
            progress.running > 0 -> "${progress.running} running • ${progress.pending} pending"
            progress.done > 0 -> "${progress.done} done • ${progress.pending} pending"
            else -> "No task activity yet"
        }
        binding.sessionStartedAt.text = TimeFormatUtils.formatApiTimestamp(summary.startedAt)
            ?.let { "Started: $it" }
            ?: "Started time unavailable"
        binding.taskProgressView.text = progress?.let {
            buildString {
                append("${it.total} task${if (it.total != 1) "s" else ""}")
                if (it.failed > 0) append(" · ${it.failed} failed")
                if (it.running > 0) append(" · ${it.running} running")
                if (it.done > 0) append(" · ${it.done} done")
                if (it.pending > 0) append(" · ${it.pending} pending")
            }
        } ?: "No task progress available."

        latestLogs = summary.recentLogs
        renderRecentLogs()

        val failureSummary = if (
            summary.status.equals("failed", true) ||
            summary.status.equals("stopped", true) ||
            progress?.failed ?: 0 > 0
        ) {
            FailureSummary.summarizeSessionFailure(summary.recentLogs.map { it.message })
        } else {
            null
        }
        binding.sessionFailureSummaryView.text = failureSummary.orEmpty()
        binding.sessionFailureCard.visibility =
            if (failureSummary.isNullOrBlank()) View.GONE else View.VISIBLE

        val isRunning = summary.status.equals("running", true)
        binding.pauseButton.visibility = if (isRunning) View.VISIBLE else View.GONE
        binding.stopButton.visibility = if (isRunning) View.VISIBLE else View.GONE
    }

    private fun bindCheckpoints(checkpoints: MobileCheckpointListResponse) {
        val bestCheckpoint = checkpoints.checkpoints.lastOrNull()

        binding.checkpointSummaryView.text = if (checkpoints.totalCount > 0) {
            val bestLabel = bestCheckpoint?.let { checkpoint ->
                val stepSuffix = checkpoint.stepIndex?.let { " • step $it" } ?: ""
                "Best resume point: ${checkpoint.name}$stepSuffix"
            }.orEmpty()
            "${checkpoints.totalCount} checkpoint(s) available\n$bestLabel"
        } else {
            "No checkpoints available yet"
        }
        val recentCheckpointLines = checkpoints.checkpoints
            .asReversed()
            .filterNot { checkpoint -> checkpoint.name == bestCheckpoint?.name }
            .take(3)
            .map { checkpoint ->
                val stepSuffix = checkpoint.stepIndex?.let { " • step $it" } ?: ""
                "- ${checkpoint.name}$stepSuffix"
            }

        binding.checkpointListView.text = if (recentCheckpointLines.isEmpty()) {
            ""
        } else {
            recentCheckpointLines.joinToString("\n")
        }
        binding.checkpointListView.visibility =
            if (recentCheckpointLines.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderRecoveryCard() {
        val summary = latestSummary ?: run {
            binding.recoveryCard.visibility = View.GONE
            return
        }
        val checkpointCount = latestCheckpoints?.totalCount ?: 0
        val status = summary.status.lowercase()
        val resumable = status == "paused" || (status == "stopped" && checkpointCount > 0)

        when {
            resumable -> {
                binding.recoveryCard.visibility = View.VISIBLE
                binding.recoveryTitle.text = getString(R.string.recovery_resume_title)
                binding.recoverySummary.text = resources.getQuantityString(
                    R.plurals.session_recovery_resume_message,
                    checkpointCount,
                    checkpointCount
                )
            }
            status == "running" -> {
                binding.recoveryCard.visibility = View.VISIBLE
                binding.recoveryTitle.text = getString(R.string.recovery_running_title)
                binding.recoverySummary.text = getString(R.string.recovery_running_message)
            }
            status == "stopped" || status == "failed" || status == "paused" -> {
                binding.recoveryCard.visibility = View.VISIBLE
                binding.recoveryTitle.text = getString(R.string.recovery_retry_title)
                binding.recoverySummary.text = getString(R.string.session_recovery_retry_message)
            }
            else -> binding.recoveryCard.visibility = View.GONE
        }

        binding.pauseButton.visibility = if (status == "running") View.VISIBLE else View.GONE
        binding.resumeButton.visibility = if (resumable) View.VISIBLE else View.GONE
        binding.stopButton.visibility = if (status == "running") View.VISIBLE else View.GONE
        binding.resumeButton.text = getString(R.string.resume_button_label)
    }

    private fun stopSession() {
        val client = orchestratorClient ?: return
        AlertDialog.Builder(this)
            .setTitle("Stop Session")
            .setMessage("Stop this running session now? You can resume later if a useful checkpoint exists.")
            .setPositiveButton("Stop") { _, _ ->
                CoroutineScope(Dispatchers.Main).launch {
                    client.stopSession(sessionId).onSuccess {
                        Snackbar.make(binding.root, it.message.ifBlank { "Session stop requested" }, Snackbar.LENGTH_SHORT).show()
                        loadSessionData(showToast = false)
                    }.onFailure { error ->
                        Snackbar.make(binding.root, error.message ?: "Failed to stop session", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resumeSession() {
        val client = orchestratorClient ?: return
        CoroutineScope(Dispatchers.Main).launch {
            client.resumeSession(sessionId).onSuccess {
                Snackbar.make(binding.root, it.message.ifBlank { "Session resume requested" }, Snackbar.LENGTH_SHORT).show()
                loadSessionData(showToast = false)
            }.onFailure { error ->
                Snackbar.make(binding.root, error.message ?: "Failed to resume session", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun setLogFilter(filter: LogFilter) {
        currentLogFilter = filter
        styleFilterButton(binding.logFilterAllButton, filter == LogFilter.ALL)
        styleFilterButton(binding.logFilterErrorsButton, filter == LogFilter.ERRORS)
        styleFilterButton(binding.logFilterOrchestrationButton, filter == LogFilter.PHASES)
        styleFilterButton(binding.logFilterCheckpointButton, filter == LogFilter.CHECKPOINTS)
        renderRecentLogs()
    }

    private fun styleFilterButton(button: com.google.android.material.button.MaterialButton, selected: Boolean) {
        if (selected) {
            button.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            button.setTextColor(ContextCompat.getColor(this, R.color.white))
            button.strokeWidth = 0
        } else {
            button.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_container_low))
            button.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            button.strokeWidth = 1
            button.strokeColor = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.outline_subtle)
            )
        }
    }

    private fun renderRecentLogs() {
        val wsConverted = wsLogEntries.map { entry ->
            RecentActivity(
                level = entry.level,
                message = entry.message,
                timestamp = entry.timestamp
            )
        }
        val combined = (wsConverted + latestLogs.asReversed()).distinctBy { it.message + it.timestamp }
        val filteredLogs = combined
            .filter { log ->
                when (currentLogFilter) {
                    LogFilter.ALL -> true
                    LogFilter.ERRORS -> {
                        log.level.equals("ERROR", true) ||
                                log.message.contains("failed", true) ||
                                log.message.contains("error", true) ||
                                log.message.contains("timeout", true)
                    }
                    LogFilter.PHASES -> {
                        log.message.contains("[ORCHESTRATION]", true) ||
                                log.message.contains("phase", true) ||
                                log.message.contains("planning", true) ||
                                log.message.contains("executing", true) ||
                                log.message.contains("debugging", true)
                    }
                    LogFilter.CHECKPOINTS -> {
                        log.message.contains("[CHECKPOINT]", true) ||
                                log.message.contains("checkpoint", true) ||
                                log.message.contains("resume", true)
                    }
                }
            }
            .take(20)

        val rendered = if (filteredLogs.isEmpty()) {
            when (currentLogFilter) {
                LogFilter.ALL -> "No recent logs yet."
                LogFilter.ERRORS -> "No recent error logs."
                LogFilter.PHASES -> "No orchestration phase logs yet."
                LogFilter.CHECKPOINTS -> "No checkpoint or resume logs yet."
            }
        } else {
            filteredLogs
                .groupBy { classifyPhase(it) }
                .entries
                .joinToString("\n\n") { (phase, logs) ->
                    buildString {
                        append(phase)
                        append("\n\n")
                        append(
                            logs.joinToString("\n\n") { log ->
                                val timestamp = TimeFormatUtils.formatApiTimestamp(log.timestamp)
                                    ?: log.timestamp.takeIf { it.isNotBlank() }
                                    ?: "Recent"
                                "$timestamp • ${log.level}\n${compactLogMessage(log.message)}"
                            }
                        )
                    }
                }
        }

        val matchCount = filteredLogs.size
        binding.logTimelineHint.text = when (currentLogFilter) {
            LogFilter.ALL -> if (matchCount > 0) "Newest first, grouped by phase. $matchCount entries." else "No recent logs yet."
            LogFilter.ERRORS -> if (matchCount > 0) "$matchCount error/failure entries." else "No errors or failures in recent logs."
            LogFilter.PHASES -> if (matchCount > 0) "$matchCount phase entries (planning, execution, debugging)." else "No phase logs in recent logs."
            LogFilter.CHECKPOINTS -> if (matchCount > 0) "$matchCount checkpoint/resume entries." else "No checkpoint activity in recent logs."
        }
        binding.recentLogsView.text = OutputHighlighter.render(this, rendered)
    }

    private fun compactLogMessage(message: String): String {
        return message
            .replace(Regex("\\s+"), " ")
            .replace(" [CHECKPOINT] ", " [CHECKPOINT] ")
            .trim()
    }

    private fun classifyPhase(log: RecentActivity): String {
        val message = log.message.lowercase()
        return when {
            message.contains("[checkpoint]") || message.contains("checkpoint") || message.contains("resume") ->
                "Checkpoint / Resume"
            message.contains("phase 1") || message.contains("planning") ->
                "Planning"
            message.contains("phase 2") || message.contains("executing") || message.contains("step ") ->
                "Executing"
            message.contains("phase 3") || message.contains("debugging") || message.contains("fix") ->
                "Debugging"
            message.contains("phase 4") || message.contains("revis") ->
                "Plan Revision"
            message.contains("completed successfully") || message.contains("phase 5") || message.contains("done") ->
                "Completed"
            log.level.equals("ERROR", true) || message.contains("failed") || message.contains("error") || message.contains("timeout") ->
                "Errors"
            else -> "Recent Activity"
        }
    }

    private fun bindIntervention(intervention: InterventionRequest?) {
        if (intervention == null) {
            binding.interventionCard.visibility = View.GONE
            return
        }
        binding.interventionCard.visibility = View.VISIBLE
        binding.interventionTypeBadge.text = intervention.interventionType.uppercase()
        binding.interventionPromptView.text = intervention.prompt

        val isApproval = intervention.interventionType.equals("approval", ignoreCase = true)
        binding.interventionApproveButton.visibility = if (isApproval) View.VISIBLE else View.GONE
        binding.interventionDenyButton.visibility = if (isApproval) View.VISIBLE else View.GONE
        binding.interventionReplyInput.visibility = if (!isApproval) View.VISIBLE else View.GONE
        binding.interventionSubmitButton.visibility = if (!isApproval) View.VISIBLE else View.GONE
    }

    private fun handleApproveIntervention() {
        val client = orchestratorClient ?: return
        val intervention = currentIntervention ?: return
        CoroutineScope(Dispatchers.Main).launch {
            client.approveIntervention(sessionId, intervention.id).onSuccess {
                Snackbar.make(binding.root, "Approved — session resuming", Snackbar.LENGTH_SHORT).show()
                loadSessionData(showToast = false)
            }.onFailure { error ->
                Snackbar.make(binding.root, error.message ?: "Failed to approve", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun handleDenyIntervention() {
        val client = orchestratorClient ?: return
        val intervention = currentIntervention ?: return
        CoroutineScope(Dispatchers.Main).launch {
            client.denyIntervention(sessionId, intervention.id).onSuccess {
                Snackbar.make(binding.root, "Denied — session resuming with denial context", Snackbar.LENGTH_SHORT).show()
                loadSessionData(showToast = false)
            }.onFailure { error ->
                Snackbar.make(binding.root, error.message ?: "Failed to deny", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun handleSubmitGuidance() {
        val client = orchestratorClient ?: return
        val intervention = currentIntervention ?: return
        val reply = binding.interventionReplyInput.text?.toString()?.trim() ?: ""
        if (reply.isBlank()) {
            Snackbar.make(binding.root, "Enter guidance before submitting", Snackbar.LENGTH_SHORT).show()
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            client.replyToIntervention(sessionId, intervention.id, reply).onSuccess {
                binding.interventionReplyInput.text?.clear()
                Snackbar.make(binding.root, "Guidance sent — session resuming", Snackbar.LENGTH_SHORT).show()
                loadSessionData(showToast = false)
            }.onFailure { error ->
                Snackbar.make(binding.root, error.message ?: "Failed to send guidance", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun bindReplanCard(fs: ExecutionFailureSummaryResponse?) {
        binding.replanCard.visibility = View.VISIBLE
        if (!fs?.summary.isNullOrBlank()) {
            binding.replanFailureSummaryView.text = fs!!.summary
            binding.replanFailureSummaryView.visibility = View.VISIBLE
        } else {
            binding.replanFailureSummaryView.visibility = View.GONE
        }
        val existingFeedback = fs?.operatorFeedback
        if (!existingFeedback.isNullOrBlank() && binding.operatorFeedbackInput.text.isNullOrBlank()) {
            binding.operatorFeedbackInput.setText(existingFeedback)
        }
    }

    private fun handleSendToProjectArchitect() {
        val client = orchestratorClient ?: return
        val feedback = binding.operatorFeedbackInput.text?.toString()?.trim().orEmpty()
        CoroutineScope(Dispatchers.Main).launch {
            if (feedback.isNotBlank()) {
                client.submitOperatorFeedback(sessionId, feedback).onFailure { error ->
                    Snackbar.make(binding.root, error.message ?: "Failed to save feedback", Snackbar.LENGTH_LONG).show()
                    return@launch
                }
            }
            client.triggerReplan(sessionId).onSuccess { result ->
                val msg = if (result.newSessionId > 0) {
                    "Replan started — session #${result.newSessionId} created"
                } else {
                    result.message.ifBlank { "Replan triggered" }
                }
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                binding.replanCard.visibility = View.GONE
            }.onFailure { error ->
                Snackbar.make(binding.root, error.message ?: "Failed to trigger replan", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
