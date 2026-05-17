package com.user.ui.tasks

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.user.ClawMobileApplication
import com.user.R
import com.user.data.MobileTaskDetailResponse
import com.user.data.Task
import com.user.data.TaskChangeSetResponse
import com.user.data.TaskStatus
import com.user.databinding.ActivityTaskDetailBinding
import com.user.ui.FailureSummary
import com.user.ui.OutputHighlighter
import com.user.service.OrchestratorApiClient
import com.user.viewmodel.TaskViewModel
import com.user.viewmodel.TaskViewModelFactory
import kotlinx.coroutines.launch

/**
 * Activity for displaying task details and performing actions
 */
class TaskDetailActivity : AppCompatActivity() {

    private enum class RecoveryAction {
        RESUME_SESSION,
        RETRY_TASK
    }

    private lateinit var binding: ActivityTaskDetailBinding
    private lateinit var viewModel: TaskViewModel
    private var orchestratorClient: OrchestratorApiClient? = null
    private var currentTask: Task? = null
    private var sessionId: String = ""
    private var recommendedRecoveryAction: RecoveryAction = RecoveryAction.RETRY_TASK
    private var recoveryReason: String? = null
    private var recoverySessionId: String? = null
    private var workspaceStatus: String? = null
    private var currentDetail: MobileTaskDetailResponse? = null
    private var latestChangeSet: TaskChangeSetResponse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Task"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val taskId = intent.getStringExtra("task_id") ?: ""
        sessionId = intent.getStringExtra("session_id") ?: ""

        binding.resultAccordionHeader.setOnClickListener {
            toggleAccordion(binding.resultContent, binding.resultChevron)
        }
        binding.errorAccordionHeader.setOnClickListener {
            toggleAccordion(binding.errorContent, binding.errorChevron)
        }

        setupViewModel(sessionId)
        loadTask(taskId)
    }

    private fun setupViewModel(sessionId: String) {
        val app = application as ClawMobileApplication
        val factory = TaskViewModelFactory(
            repository = app.repository,
            sessionId = sessionId,
            orchestratorClient = if (app.prefsManager.isOrchestratorConfigured()) {
                OrchestratorApiClient(
                    prefs = app.prefsManager,
                    gatewayToken = app.prefsManager.gatewayToken
                ).also { orchestratorClient = it }
            } else null
        )
        viewModel = ViewModelProvider(this, factory)[TaskViewModel::class.java]
    }

    private fun loadTask(taskId: String) {
        viewModel.getTask(taskId)
        viewModel.currentTask.observe(this) { task ->
            currentTask = task
            if (task != null) {
                supportActionBar?.title = task.title
                bindTask(task)
                loadWorkspaceStatusFromApi(taskId)
            } else {
                supportActionBar?.title = "Task"
                binding.taskTitle.text = "Task unavailable"
                binding.taskDescription.text = "This task could not be loaded from local storage or Orchestrator."
                binding.taskMetaRow.visibility = android.view.View.GONE
                binding.taskResult.visibility = android.view.View.GONE
                binding.taskResultLabel.visibility = android.view.View.GONE
                binding.taskError.visibility = android.view.View.GONE
                binding.taskErrorLabel.visibility = android.view.View.GONE
                binding.recoveryCard.visibility = View.GONE
                binding.workspaceReviewCard.visibility = View.GONE
                binding.approveButton.visibility = android.view.View.GONE
                binding.rejectButton.visibility = android.view.View.GONE
                binding.startButton.visibility = android.view.View.GONE
            }
        }
        viewModel.reviewResult.observe(this) { result ->
            result.onSuccess {
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root, R.string.workspace_review_success, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).show()
                loadTask(taskId)
            }.onFailure { error ->
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root, error.message ?: getString(R.string.workspace_review_failed),
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadWorkspaceStatusFromApi(taskId: String) {
        val client = orchestratorClient ?: return
        lifecycleScope.launch {
            client.getTaskDetail(taskId).onSuccess { detail ->
                currentDetail = detail
                workspaceStatus = detail.workspaceStatus
                updateWorkspaceReviewCard()
            }
            client.getLatestTaskChangeSet(taskId).onSuccess { changeSet ->
                latestChangeSet = changeSet
                updateChangeSetSummary(changeSet)
                updateWorkspaceReviewCard()
            }.onFailure {
                // No change set recorded yet — normal for older tasks
            }
        }
    }

    private fun updateChangeSetSummary(changeSet: TaskChangeSetResponse) {
        val cs = changeSet.changeSet
        if (cs == null || cs.changedCount == 0) {
            binding.changeSetSummaryRow.visibility = View.GONE
            return
        }
        binding.changeSetAdded.text = "+${cs.addedCount} added"
        binding.changeSetModified.text = "~${cs.modifiedCount} modified"
        binding.changeSetDeleted.text = "-${cs.deletedCount} deleted"
        binding.changeSetSummaryRow.visibility = View.VISIBLE
    }

    private fun updateWorkspaceReviewCard() {
        val task = currentTask ?: return
        val isDone = task.status == TaskStatus.COMPLETED
        val hasChangeSet = latestChangeSet?.changeSet?.let { it.changedCount > 0 } == true
        val pendingExecutionId = latestChangeSet?.taskExecutionId

        when {
            !isDone -> {
                binding.workspaceReviewCard.visibility = View.GONE
                binding.rejectChangeSetButton.visibility = View.GONE
            }
            workspaceStatus == null || workspaceStatus == "ready" -> {
                binding.workspaceReviewCard.visibility = View.VISIBLE
                binding.reviewNoteLayout.visibility = View.VISIBLE
                binding.promoteButton.visibility = View.VISIBLE
                binding.requestChangesButton.visibility = View.VISIBLE
                binding.workspaceResolvedBadge.visibility = View.GONE
                binding.promotionNoteText.visibility = View.GONE
                binding.rejectChangeSetButton.visibility =
                    if (hasChangeSet && pendingExecutionId != null) View.VISIBLE else View.GONE
                binding.promoteButton.setOnClickListener { submitReview("promote") }
                binding.requestChangesButton.setOnClickListener { submitReview("request_changes") }
                if (hasChangeSet && pendingExecutionId != null) {
                    binding.rejectChangeSetButton.setOnClickListener {
                        confirmRejectChangeSet(task.taskId, pendingExecutionId)
                    }
                }
            }
            else -> {
                binding.workspaceReviewCard.visibility = View.VISIBLE
                binding.reviewNoteLayout.visibility = View.GONE
                binding.promoteButton.visibility = View.GONE
                binding.requestChangesButton.visibility = View.GONE
                binding.rejectChangeSetButton.visibility = View.GONE
                binding.workspaceResolvedBadge.visibility = View.VISIBLE
                binding.workspaceResolvedBadge.text = when (workspaceStatus) {
                    "promoted" -> getString(R.string.workspace_review_promoted_badge)
                    "changes_requested" -> getString(R.string.workspace_review_changes_requested_badge)
                    else -> workspaceStatus
                }
                val note = currentDetail?.promotionNote?.takeIf { it.isNotBlank() }
                if (note != null) {
                    binding.promotionNoteText.text = note
                    binding.promotionNoteText.visibility = View.VISIBLE
                } else {
                    binding.promotionNoteText.visibility = View.GONE
                }
            }
        }
    }

    private fun confirmRejectChangeSet(taskId: String, taskExecutionId: Int) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Reject & Restore")
            .setMessage("Archive this candidate change set and restore the pre-task snapshot?")
            .setPositiveButton("Reject & Restore") { _, _ -> rejectChangeSet(taskId, taskExecutionId) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rejectChangeSet(taskId: String, taskExecutionId: Int) {
        val client = orchestratorClient ?: return
        binding.rejectChangeSetButton.isEnabled = false
        lifecycleScope.launch {
            client.rejectTaskChangeSet(taskId, taskExecutionId).onSuccess {
                latestChangeSet = null
                workspaceStatus = "changes_requested"
                binding.changeSetSummaryRow.visibility = View.GONE
                updateWorkspaceReviewCard()
                Toast.makeText(this@TaskDetailActivity, "Change set rejected and workspace restored", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                binding.rejectChangeSetButton.isEnabled = true
                Toast.makeText(this@TaskDetailActivity, error.message ?: "Reject failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun submitReview(action: String) {
        val task = currentTask ?: return
        val note = binding.reviewNoteInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        binding.promoteButton.isEnabled = false
        binding.requestChangesButton.isEnabled = false
        viewModel.submitReview(task.taskId, action, note)
    }

    private fun bindTask(task: Task) {
        // Update UI elements
        binding.taskTitle.text = task.title
        binding.taskDescription.text = task.description
        binding.taskTime.text = formatTime(task.createdAt)

        if (task.priority > 0) {
            binding.taskPriority.text = task.priority.toString()
            binding.taskPriorityLabel.visibility = android.view.View.VISIBLE
            binding.taskPriority.visibility = android.view.View.VISIBLE
        } else {
            binding.taskPriorityLabel.visibility = android.view.View.GONE
            binding.taskPriority.visibility = android.view.View.GONE
        }

        if (task.createdAt > 0L) {
            binding.taskTime.text = formatTime(task.createdAt)
            binding.taskTime.visibility = android.view.View.VISIBLE
        } else {
            binding.taskTime.visibility = android.view.View.GONE
        }

        val showMetaRow =
            binding.taskPriorityLabel.visibility == android.view.View.VISIBLE ||
                    binding.taskTime.visibility == android.view.View.VISIBLE
        binding.taskMetaRow.visibility = if (showMetaRow) android.view.View.VISIBLE else android.view.View.GONE

        // Update status badge
        updateStatusBadge(task.status)

        // Update result and error
        val failureSummary = if (task.status == TaskStatus.FAILED || task.status == TaskStatus.TIMEOUT) {
            FailureSummary.summarizeTaskFailure(task.error, task.result)
        } else {
            null
        }
        binding.failureSummaryView.text = failureSummary.orEmpty()
        binding.failureSummaryCard.visibility =
            if (failureSummary.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE

        bindRawOutput(task)

        updateRecoveryState(task)
        updateWorkspaceReviewCard()

        // Show/hide action buttons based on status
        updateActionButtons(task.status)
    }

    private fun bindRawOutput(task: Task) {
        val resultText = task.result?.trim().orEmpty()
        val errorText = task.error?.trim().orEmpty()
        val isFailureState = task.status == TaskStatus.FAILED || task.status == TaskStatus.TIMEOUT
        val resultDuplicatesError = resultText.isNotBlank() && errorText.isNotBlank() &&
                textsAreEffectivelySame(resultText, errorText)

        val primaryLabel: String
        val primaryText: String
        val primaryIsError: Boolean
        val secondaryLabel: String?
        val secondaryText: String?
        val secondaryIsError: Boolean

        if (isFailureState && errorText.isNotBlank()) {
            primaryLabel = getString(R.string.task_output_error_primary)
            primaryText = errorText
            primaryIsError = true
            if (resultText.isNotBlank() && !resultDuplicatesError) {
                secondaryLabel = getString(R.string.task_output_recent_output)
                secondaryText = resultText
                secondaryIsError = false
            } else {
                secondaryLabel = null
                secondaryText = null
                secondaryIsError = false
            }
        } else if (resultText.isNotBlank()) {
            primaryLabel = getString(R.string.task_output_result)
            primaryText = resultText
            primaryIsError = false
            if (errorText.isNotBlank() && !resultDuplicatesError) {
                secondaryLabel = getString(R.string.task_output_error_secondary)
                secondaryText = errorText
                secondaryIsError = true
            } else {
                secondaryLabel = null
                secondaryText = null
                secondaryIsError = false
            }
        } else if (errorText.isNotBlank()) {
            primaryLabel = getString(R.string.task_output_error_primary)
            primaryText = errorText
            primaryIsError = true
            secondaryLabel = null
            secondaryText = null
            secondaryIsError = false
        } else {
            primaryLabel = ""
            primaryText = ""
            primaryIsError = false
            secondaryLabel = null
            secondaryText = null
            secondaryIsError = false
        }

        val hasPrimary = primaryText.isNotBlank()
        binding.resultAccordionCard.visibility = if (hasPrimary) View.VISIBLE else View.GONE
        binding.taskResultLabel.text = primaryLabel
        binding.taskResult.text = OutputHighlighter.render(this, primaryText, isError = primaryIsError)
        binding.taskResultLabel.visibility = if (hasPrimary) View.VISIBLE else View.GONE
        binding.taskResult.visibility = if (hasPrimary) View.VISIBLE else View.GONE

        val hasSecondary = !secondaryText.isNullOrBlank()
        binding.errorAccordionCard.visibility = if (hasSecondary) View.VISIBLE else View.GONE
        binding.taskErrorLabel.text = secondaryLabel.orEmpty()
        binding.taskError.text = OutputHighlighter.render(
            this,
            secondaryText.orEmpty(),
            isError = secondaryIsError
        )
        binding.taskErrorLabel.visibility = if (hasSecondary) View.VISIBLE else View.GONE
        binding.taskError.visibility = if (hasSecondary) View.VISIBLE else View.GONE
    }

    private fun textsAreEffectivelySame(first: String, second: String): Boolean {
        val normalizedFirst = normalizeForComparison(first)
        val normalizedSecond = normalizeForComparison(second)
        return normalizedFirst == normalizedSecond ||
                normalizedFirst.contains(normalizedSecond) ||
                normalizedSecond.contains(normalizedFirst)
    }

    private fun normalizeForComparison(value: String): String {
        return value
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }

    private fun updateRecoveryState(task: Task) {
        val linkedSessionId = task.sessionId?.takeIf { it.isNotBlank() }
        if (task.status != TaskStatus.FAILED && task.status != TaskStatus.TIMEOUT) {
            recoverySessionId = linkedSessionId
            recoveryReason = null
            binding.recoveryCard.visibility = View.GONE
            return
        }

        if (linkedSessionId == null || orchestratorClient == null) {
            recommendedRecoveryAction = RecoveryAction.RETRY_TASK
            recoverySessionId = linkedSessionId
            recoveryReason = getString(R.string.recovery_retry_reason)
            renderRecoveryCard()
            return
        }

        recoverySessionId = linkedSessionId
        binding.recoveryCard.visibility = View.VISIBLE
        binding.recoveryActionTitle.text = getString(R.string.recovery_loading_title)
        binding.recoveryActionSummary.text = getString(R.string.recovery_loading_message)
        loadRecoveryRecommendation(linkedSessionId)
    }

    private fun loadRecoveryRecommendation(linkedSessionId: String) {
        val client = orchestratorClient ?: return
        lifecycleScope.launch {
            val summaryResult = client.getSessionSummary(linkedSessionId)
            val checkpointsResult = client.getSessionCheckpoints(linkedSessionId)

            val checkpointCount = checkpointsResult.getOrNull()?.totalCount ?: 0
            val sessionStatus = summaryResult.getOrNull()?.status?.lowercase()

            recommendedRecoveryAction = if (
                checkpointCount > 0 && sessionStatus != "running" && sessionStatus != "completed"
            ) {
                RecoveryAction.RESUME_SESSION
            } else {
                RecoveryAction.RETRY_TASK
            }

            recoveryReason = when {
                checkpointCount > 0 && recommendedRecoveryAction == RecoveryAction.RESUME_SESSION -> {
                    resources.getQuantityString(
                        R.plurals.recovery_resume_reason,
                        checkpointCount,
                        checkpointCount
                    )
                }
                sessionStatus == "running" -> getString(R.string.recovery_running_reason)
                sessionStatus == "completed" -> getString(R.string.recovery_completed_reason)
                else -> getString(R.string.recovery_retry_reason)
            }

            renderRecoveryCard()
            currentTask?.let { updateActionButtons(it.status) }
        }
    }

    private fun renderRecoveryCard() {
        binding.recoveryCard.visibility = View.VISIBLE
        when (recommendedRecoveryAction) {
            RecoveryAction.RESUME_SESSION -> {
                binding.recoveryActionTitle.text = getString(R.string.recovery_resume_title)
                binding.recoveryActionSummary.text = getString(
                    R.string.recovery_resume_message,
                    recoveryReason ?: getString(R.string.recovery_resume_reason_fallback)
                )
            }
            RecoveryAction.RETRY_TASK -> {
                binding.recoveryActionTitle.text = getString(R.string.recovery_retry_title)
                binding.recoveryActionSummary.text = getString(
                    R.string.recovery_retry_message,
                    recoveryReason ?: getString(R.string.recovery_retry_reason)
                )
            }
        }
    }

    private fun updateStatusBadge(status: TaskStatus) {
        val badge = binding.taskStatusBadge
        badge.text = status.name

        val drawableRes = when (status) {
            TaskStatus.PENDING -> R.drawable.badge_pending
            TaskStatus.APPROVED -> R.drawable.badge_approved
            TaskStatus.IN_PROGRESS -> R.drawable.badge_running
            TaskStatus.COMPLETED -> R.drawable.badge_completed
            TaskStatus.FAILED -> R.drawable.badge_failed
            TaskStatus.REJECTED -> R.drawable.badge_rejected
            TaskStatus.TIMEOUT -> R.drawable.badge_timeout
        }
        badge.setBackgroundResource(drawableRes)
    }

    private fun updateActionButtons(status: TaskStatus) {
        val approveBtn = binding.approveButton
        val rejectBtn = binding.rejectButton
        val openSessionBtn = binding.openSessionButton
        val startBtn = binding.startButton
        val linkedSessionId = currentTask?.sessionId?.takeIf { it.isNotBlank() }
        val canOpenSession = linkedSessionId != null && status !in setOf(
            TaskStatus.PENDING,
            TaskStatus.APPROVED,
            TaskStatus.FAILED,
            TaskStatus.TIMEOUT,
        )

        openSessionBtn.visibility = if (canOpenSession) View.VISIBLE else View.GONE
        if (canOpenSession) {
            openSessionBtn.setOnClickListener {
                startActivity(
                    Intent(this, SessionDetailActivity::class.java).apply {
                        putExtra("session_id", linkedSessionId)
                        putExtra("session_name", "${currentTask?.title}_session")
                    }
                )
            }
        }

        when (status) {
            TaskStatus.PENDING -> {
                approveBtn.visibility = if (sessionId.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
                rejectBtn.visibility = if (sessionId.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
                startBtn.visibility = android.view.View.GONE
                approveBtn.setOnClickListener { viewModel.approveTask(currentTask?.taskId ?: "") }
                rejectBtn.setOnClickListener {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Reject Task")
                        .setMessage("Enter reason for rejection (optional):")
                        .setPositiveButton("Reject") { _, _ ->
                            viewModel.rejectTask(currentTask?.taskId ?: "", null)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            TaskStatus.APPROVED -> {
                approveBtn.visibility = android.view.View.GONE
                rejectBtn.visibility = android.view.View.GONE
                startBtn.visibility = if (sessionId.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
                startBtn.setOnClickListener { viewModel.startTask(currentTask?.taskId ?: "") }
            }
            TaskStatus.IN_PROGRESS -> {
                approveBtn.visibility = android.view.View.GONE
                rejectBtn.visibility = android.view.View.GONE
                startBtn.visibility = android.view.View.GONE
            }
            TaskStatus.FAILED, TaskStatus.TIMEOUT -> {
                approveBtn.visibility = android.view.View.GONE
                rejectBtn.visibility = android.view.View.GONE
                openSessionBtn.visibility = android.view.View.GONE
                startBtn.visibility =
                    if (orchestratorClient != null) android.view.View.VISIBLE else android.view.View.GONE
                if (recommendedRecoveryAction == RecoveryAction.RESUME_SESSION && recoverySessionId != null) {
                    startBtn.text = getString(R.string.resume_session_action)
                    startBtn.setOnClickListener { confirmResumeSession(recoverySessionId!!) }
                } else {
                    startBtn.text = getString(R.string.retry_task)
                    startBtn.setOnClickListener { confirmRetryTask() }
                }
            }
            TaskStatus.COMPLETED, TaskStatus.REJECTED -> {
                approveBtn.visibility = android.view.View.GONE
                rejectBtn.visibility = android.view.View.GONE
                startBtn.visibility = android.view.View.GONE
            }
        }
    }

    private fun confirmRetryTask() {
        val task = currentTask ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.retry_task_title)
            .setMessage(R.string.retry_task_message)
            .setPositiveButton(R.string.retry_task) { _, _ ->
                retryTask(task)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmResumeSession(linkedSessionId: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.resume_session_title)
            .setMessage(R.string.resume_session_message)
            .setPositiveButton(R.string.resume_session_action) { _, _ ->
                resumeSession(linkedSessionId)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun retryTask(task: Task) {
        val client = orchestratorClient ?: return
        binding.startButton.isEnabled = false
        lifecycleScope.launch {
            client.retryTask(task.taskId)
                .onSuccess { response ->
                    val refreshedTask = task.copy(
                        status = TaskStatus.IN_PROGRESS,
                        error = null
                    )
                    currentTask = refreshedTask
                    bindTask(refreshedTask)
                    binding.startButton.isEnabled = true
                    if (response.sessionId != null) {
                        sessionId = response.sessionId.toString()
                    }
                    Toast.makeText(
                        this@TaskDetailActivity,
                        response.message.ifBlank { getString(R.string.retry_task_success) },
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .onFailure { error ->
                    binding.startButton.isEnabled = true
                    Toast.makeText(
                        this@TaskDetailActivity,
                        error.message ?: getString(R.string.retry_task_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun resumeSession(linkedSessionId: String) {
        val client = orchestratorClient ?: return
        binding.startButton.isEnabled = false
        lifecycleScope.launch {
            client.resumeSession(linkedSessionId)
                .onSuccess { response ->
                    binding.startButton.isEnabled = true
                    Toast.makeText(
                        this@TaskDetailActivity,
                        response.message.ifBlank { getString(R.string.resume_session_success) },
                        Toast.LENGTH_SHORT
                    ).show()
                    currentTask = currentTask?.copy(status = TaskStatus.IN_PROGRESS, error = null)
                    currentTask?.let(::bindTask)
                }
                .onFailure { error ->
                    binding.startButton.isEnabled = true
                    Toast.makeText(
                        this@TaskDetailActivity,
                        error.message ?: getString(R.string.resume_session_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun formatTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 60_000 -> "${diff / 1000}s ago"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86400_000 -> "${diff / 3600_000}h ago"
            else -> "${diff / 86400_000}d ago"
        }
    }

    private fun toggleAccordion(content: android.view.View, chevron: android.widget.ImageView) {
        val expanding = content.visibility != android.view.View.VISIBLE
        content.visibility = if (expanding) android.view.View.VISIBLE else android.view.View.GONE
        chevron.animate().rotation(if (expanding) 180f else 0f).setDuration(200).start()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}