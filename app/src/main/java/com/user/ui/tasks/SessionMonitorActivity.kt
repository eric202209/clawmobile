package com.user.ui.tasks

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.user.ClawMobileApplication
import com.user.R
import com.user.data.MobileNarrativeTimeline
import com.user.data.MobileRecoveryContext
import com.user.data.MobileSessionSummaryResponse
import com.user.data.NarrativeTimelinePhase
import com.user.databinding.ActivitySessionMonitorBinding
import com.user.service.OrchestratorApiClient
import com.user.ui.TimeFormatUtils
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionMonitorActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionMonitorBinding
    private var client: OrchestratorApiClient? = null
    private var sessionId: String = ""
    private var sessionName: String = "Session"

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            loadAll(showToast = false)
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 30_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionMonitorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionId = intent.getStringExtra("session_id") ?: ""
        sessionName = intent.getStringExtra("session_name") ?: "Session"

        val app = application as ClawMobileApplication
        if (app.prefsManager.isOrchestratorConfigured()) {
            client = OrchestratorApiClient(
                prefs = app.prefsManager,
                gatewayToken = app.prefsManager.gatewayToken
            )
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = sessionName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.refreshButton.setOnClickListener { loadAll(showToast = true) }
        binding.resumeButton.setOnClickListener { confirmResume() }
        binding.pauseButton.setOnClickListener { confirmPause() }
        binding.stopButton.setOnClickListener { confirmStop() }

        binding.timelineAccordionHeader.setOnClickListener {
            val expanding = binding.timelineContent.visibility != View.VISIBLE
            binding.timelineContent.visibility = if (expanding) View.VISIBLE else View.GONE
            binding.timelineChevron.animate()
                .rotation(if (expanding) 180f else 0f)
                .setDuration(200)
                .start()
        }

        if (client == null) {
            Snackbar.make(binding.root, "Orchestrator is not configured.", Snackbar.LENGTH_LONG).show()
        } else {
            loadAll(showToast = false)
        }
    }

    override fun onResume() {
        super.onResume()
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
    }

    // ── Data loading ──────────────────────────────────────────────

    private fun loadAll(showToast: Boolean) {
        val c = client ?: return
        lifecycleScope.launch {
            // Fire all three in parallel; each updates its card independently
            launch { loadSummary(c, showToast) }
            launch { loadRecoveryContext(c) }
            launch { loadTimeline(c) }
        }
    }

    private suspend fun loadSummary(c: OrchestratorApiClient, showToast: Boolean) {
        c.getSessionSummary(sessionId).onSuccess { summary ->
            runOnUiThread {
                bindSummary(summary)
                if (showToast) Snackbar.make(binding.root, "Refreshed", Snackbar.LENGTH_SHORT).show()
            }
        }.onFailure { error ->
            runOnUiThread {
                Snackbar.make(binding.root, error.message ?: "Failed to load summary", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun loadRecoveryContext(c: OrchestratorApiClient) {
        c.getRecoveryContext(sessionId).onSuccess { ctx ->
            runOnUiThread { bindRecoveryContext(ctx) }
        }
        // Recovery context is optional — silent failure is fine
    }

    private suspend fun loadTimeline(c: OrchestratorApiClient) {
        c.getNarrativeTimeline(sessionId).onSuccess { timeline ->
            runOnUiThread { bindTimeline(timeline) }
        }
        // Timeline is optional — silent failure is fine
    }

    // ── Bind: summary ─────────────────────────────────────────────

    private fun bindSummary(summary: MobileSessionSummaryResponse) {
        val status = summary.status.lowercase()
        val label = when (status) {
            "awaiting_input" -> "WAITING"
            else -> summary.status.uppercase()
        }
        binding.statusBadge.text = label
        binding.statusBadge.setBackgroundResource(statusBadgeDrawable(status))

        binding.sessionStartedAt.text = TimeFormatUtils.formatApiTimestamp(summary.startedAt)
            ?.let { "Started $it" } ?: ""

        binding.refreshedAt.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        // Action buttons
        val isRunning = status == "running"
        val isPaused = status == "paused"
        val isStopped = status == "stopped" || status == "failed" || status == "cancelled" || status == "canceled"
        binding.resumeButton.visibility = if (isPaused || isStopped) View.VISIBLE else View.GONE
        binding.pauseButton.visibility = if (isRunning) View.VISIBLE else View.GONE
        binding.stopButton.visibility = if (isRunning || isPaused) View.VISIBLE else View.GONE

        // Alert card from summary.active_alert
        val alert = summary.activeAlert
        if (alert?.message != null) {
            binding.alertMessage.text = alert.message
            binding.alertCard.visibility = View.VISIBLE
        } else {
            binding.alertCard.visibility = View.GONE
        }
        // (Recovery context may also supply stop_reasons — bindRecoveryContext fills alertCard too)
    }

    // ── Bind: recovery context ────────────────────────────────────

    private fun bindRecoveryContext(ctx: MobileRecoveryContext) {
        // Alert card — prefer active_alert from summary; fall back to stop_reasons from context
        if (binding.alertCard.visibility != View.VISIBLE && ctx.stopReasons.isNotEmpty()) {
            binding.alertMessage.text = ctx.stopReasons.joinToString("\n")
            binding.alertCard.visibility = View.VISIBLE
        }

        // Current / failed task
        val focusTask = ctx.tasks.firstOrNull { it.status == "failed" }
            ?: ctx.tasks.firstOrNull { it.status == "running" }
        if (focusTask != null) {
            binding.taskCardLabel.text = if (focusTask.status == "failed") "Failed Task" else "Current Task"
            binding.taskTitle.text = focusTask.title
            if (focusTask.repairAttempts > 0) {
                binding.taskRepairBadge.text = "${focusTask.repairAttempts} repair${if (focusTask.repairAttempts == 1) "" else "s"}"
                binding.taskRepairBadge.visibility = View.VISIBLE
            } else {
                binding.taskRepairBadge.visibility = View.GONE
            }
            binding.taskCard.visibility = View.VISIBLE
        } else {
            binding.taskCard.visibility = View.GONE
        }

        // Preserved state
        val p = ctx.preserved
        val lines = buildList {
            add(if (p.completedTasksCheckpointed) "✓ Completed tasks checkpointed" else "✗ No completed tasks yet")
            add(if (p.conversationHistoryResumable) "✓ Conversation context resumable" else "✗ Conversation context not available")
            add(if (p.failedTaskRolledBack) "✓ Failed task changes rolled back" else "✗ Changes not committed")
            add(if (p.remainingPlanIntact) "✓ Remaining plan intact" else "✗ All plan tasks attempted")
        }
        binding.preservedLines.text = lines.joinToString("\n")
        binding.preservedCard.visibility = View.VISIBLE

        // Recommended actions as display-only chips
        if (ctx.recommendedActions.isNotEmpty()) {
            binding.actionsChipGroup.removeAllViews()
            ctx.recommendedActions.take(5).forEach { action ->
                val chip = Chip(this).apply {
                    text = action.label
                    isClickable = false
                    isFocusable = false
                    isCheckedIconVisible = false
                    setChipBackgroundColorResource(R.color.surface_container_high)
                    setTextColor(ContextCompat.getColor(this@SessionMonitorActivity, R.color.text_primary))
                    chipStrokeWidth = 1f
                    setChipStrokeColorResource(R.color.outline_subtle)
                }
                binding.actionsChipGroup.addView(chip)
            }
            binding.actionsCard.visibility = View.VISIBLE
        } else {
            binding.actionsChipGroup.removeAllViews()
            binding.actionsCard.visibility = View.GONE
        }
    }

    // ── Bind: narrative timeline ──────────────────────────────────

    private fun bindTimeline(timeline: MobileNarrativeTimeline) {
        binding.timelineEventCount.text = "${timeline.eventCount} events"

        if (timeline.phases.isEmpty()) {
            binding.timelineEmptyHint.visibility = View.VISIBLE
            binding.timelinePhasesContainer.removeAllViews()
            binding.latestEventCard.visibility = View.GONE
            return
        }
        binding.timelineEmptyHint.visibility = View.GONE

        // Latest event: last phase, first event
        val lastPhase = timeline.phases.last()
        val latestEvent = lastPhase.events.firstOrNull()
        if (latestEvent != null) {
            binding.latestEventTitle.text = latestEvent.title
            if (!latestEvent.detail.isNullOrBlank()) {
                binding.latestEventDetail.text = latestEvent.detail
                binding.latestEventDetail.visibility = View.VISIBLE
            } else {
                binding.latestEventDetail.visibility = View.GONE
            }
            binding.latestEventAt.text = TimeFormatUtils.formatApiTimestamp(latestEvent.at) ?: ""
            binding.latestEventCard.visibility = View.VISIBLE
        } else {
            binding.latestEventCard.visibility = View.GONE
        }

        // Build phase rows
        binding.timelinePhasesContainer.removeAllViews()
        timeline.phases.forEach { phase -> addPhaseRow(phase) }
    }

    private fun addPhaseRow(phase: NarrativeTimelinePhase) {
        val container = binding.timelinePhasesContainer

        // Phase header
        val header = TextView(this).apply {
            text = phase.title.uppercase()
            setTextColor(ContextCompat.getColor(this@SessionMonitorActivity, R.color.text_secondary))
            textSize = 11f
            letterSpacing = 0.08f
            val vPad = resources.getDimensionPixelSize(R.dimen.space_sm)
            val topMargin = resources.getDimensionPixelSize(R.dimen.space_md)
            setPadding(0, vPad, 0, vPad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, topMargin, 0, 0) }
            layoutParams = lp
        }
        container.addView(header)

        // Events (up to 6 per phase)
        phase.events.take(6).forEach { event ->
            val eventView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val topMargin = resources.getDimensionPixelSize(R.dimen.space_xs)
                val hPad = resources.getDimensionPixelSize(R.dimen.space_md)
                val vPad = resources.getDimensionPixelSize(R.dimen.space_sm)
                setPadding(hPad, vPad, hPad, vPad)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, topMargin, 0, 0) }
                layoutParams = lp
                // Border color by kind
                val bgRes = when (event.kind) {
                    "failure" -> R.drawable.bg_event_failure
                    "checkpoint" -> R.drawable.bg_event_checkpoint
                    "warning" -> R.drawable.bg_event_warning
                    else -> R.drawable.bg_event_default
                }
                setBackgroundResource(bgRes)
            }

            val titleView = TextView(this).apply {
                text = event.title
                setTextColor(ContextCompat.getColor(this@SessionMonitorActivity, R.color.text_primary))
                textSize = 13f
            }
            eventView.addView(titleView)

            if (!event.detail.isNullOrBlank()) {
                val detailView = TextView(this).apply {
                    text = event.detail
                    setTextColor(ContextCompat.getColor(this@SessionMonitorActivity, R.color.text_secondary))
                    textSize = 12f
                    val topMargin = resources.getDimensionPixelSize(R.dimen.space_xs)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, topMargin, 0, 0) }
                    layoutParams = lp
                }
                eventView.addView(detailView)
            }

            TimeFormatUtils.formatApiTimestamp(event.at)?.let { formatted ->
                val timeView = TextView(this).apply {
                    text = formatted
                    setTextColor(ContextCompat.getColor(this@SessionMonitorActivity, R.color.text_secondary))
                    textSize = 11f
                    val topMargin = resources.getDimensionPixelSize(R.dimen.space_xs)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, topMargin, 0, 0) }
                    layoutParams = lp
                }
                eventView.addView(timeView)
            }

            container.addView(eventView)
        }
    }

    // ── Actions with confirmation ─────────────────────────────────

    private fun confirmResume() {
        AlertDialog.Builder(this)
            .setTitle("Resume Session")
            .setMessage("Resume this session from its last checkpoint?")
            .setPositiveButton("Resume") { _, _ -> doResume() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmPause() {
        AlertDialog.Builder(this)
            .setTitle("Pause Session")
            .setMessage("Pause the session? You can resume it at any time.")
            .setPositiveButton("Pause") { _, _ -> doPause() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle("Stop Session")
            .setMessage("Stop this session? You can resume later if a checkpoint exists.")
            .setPositiveButton("Stop") { _, _ -> doStop() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doResume() {
        val c = client ?: return
        binding.resumeButton.isEnabled = false
        lifecycleScope.launch {
            c.resumeSession(sessionId).onSuccess {
                Snackbar.make(binding.root, it.message.ifBlank { "Session resuming" }, Snackbar.LENGTH_SHORT).show()
                loadAll(showToast = false)
            }.onFailure { error ->
                Snackbar.make(binding.root, error.message ?: "Failed to resume", Snackbar.LENGTH_LONG).show()
            }
            runOnUiThread { binding.resumeButton.isEnabled = true }
        }
    }

    private fun doPause() {
        val c = client ?: return
        binding.pauseButton.isEnabled = false
        lifecycleScope.launch {
            c.pauseSession(sessionId).onSuccess {
                Snackbar.make(binding.root, it.message.ifBlank { "Session paused" }, Snackbar.LENGTH_SHORT).show()
                loadAll(showToast = false)
            }.onFailure { error ->
                Snackbar.make(binding.root, error.message ?: "Failed to pause", Snackbar.LENGTH_LONG).show()
            }
            runOnUiThread { binding.pauseButton.isEnabled = true }
        }
    }

    private fun doStop() {
        val c = client ?: return
        binding.stopButton.isEnabled = false
        lifecycleScope.launch {
            c.stopSession(sessionId).onSuccess {
                Snackbar.make(binding.root, it.message.ifBlank { "Session stopped" }, Snackbar.LENGTH_SHORT).show()
                loadAll(showToast = false)
            }.onFailure { error ->
                Snackbar.make(binding.root, error.message ?: "Failed to stop", Snackbar.LENGTH_LONG).show()
            }
            runOnUiThread { binding.stopButton.isEnabled = true }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun statusBadgeDrawable(status: String) = when (status) {
        "running" -> R.drawable.badge_running
        "paused", "awaiting_input" -> R.drawable.badge_pending
        "stopped", "failed", "cancelled", "canceled" -> R.drawable.badge_timeout
        "completed" -> R.drawable.badge_completed
        else -> R.drawable.badge_pending
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
