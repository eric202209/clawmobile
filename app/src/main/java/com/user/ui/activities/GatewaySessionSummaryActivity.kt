package com.user.ui.activities

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.user.ClawMobileApplication
import com.user.R
import com.user.data.MobileSessionSummaryResponse
import com.user.databinding.ActivityGatewaySessionSummaryBinding
import com.user.service.OrchestratorApiClient
import com.user.ui.TimeFormatUtils
import kotlinx.coroutines.launch

class GatewaySessionSummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGatewaySessionSummaryBinding
    private var client: OrchestratorApiClient? = null
    private var sessionId: String = ""
    private var sessionName: String = "Session"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGatewaySessionSummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionId = intent.getStringExtra("session_id").orEmpty()
        sessionName = intent.getStringExtra("session_name").orEmpty().ifBlank { "Session #$sessionId" }

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

        binding.refreshButton.setOnClickListener { loadSummary(showToast = true) }
        loadSummary(showToast = false)
    }

    private fun loadSummary(showToast: Boolean) {
        val c = client ?: run {
            showError("Orchestrator is not configured on this device.")
            return
        }
        if (sessionId.isBlank()) {
            showError("Session id is missing.")
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.errorCard.visibility = View.GONE

        lifecycleScope.launch {
            c.getSessionSummary(sessionId).onSuccess { summary ->
                binding.progressBar.visibility = View.GONE
                bindSummary(summary)
                if (showToast) {
                    Snackbar.make(binding.root, "Session refreshed", Snackbar.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                binding.progressBar.visibility = View.GONE
                showError(error.message ?: "Unable to load session summary.")
            }
        }
    }

    private fun bindSummary(summary: MobileSessionSummaryResponse) {
        binding.summaryContent.visibility = View.VISIBLE
        binding.sessionStatusBadge.text = summary.status.ifBlank { "unknown" }.uppercase()
        binding.sessionStatusBadge.setBackgroundResource(statusBadgeDrawable(summary.status))
        binding.sessionStartedAt.text = TimeFormatUtils.formatApiTimestamp(summary.startedAt)
            ?.let { "Started $it" }
            ?: "Started time unavailable"
        binding.sessionMode.text = summary.executionMode.ifBlank { "automatic" }

        val progress = summary.taskProgress
        binding.taskProgress.text = if (progress == null) {
            "No task progress available yet"
        } else {
            "Total ${progress.total} | Running ${progress.running} | Pending ${progress.pending} | Done ${progress.done} | Failed ${progress.failed}"
        }

        val alert = summary.activeAlert?.message?.takeIf { it.isNotBlank() }
        binding.alertCard.visibility = if (alert == null) View.GONE else View.VISIBLE
        binding.alertMessage.text = alert.orEmpty()

        val logs = summary.recentLogs.takeLast(10)
        binding.recentLogs.text = if (logs.isEmpty()) {
            "No recent logs available."
        } else {
            logs.joinToString("\n\n") { log ->
                val time = TimeFormatUtils.formatApiTimestamp(log.timestamp).orEmpty()
                val prefix = listOf(log.level, time).filter { it.isNotBlank() }.joinToString(" | ")
                if (prefix.isBlank()) log.message else "$prefix\n${log.message}"
            }
        }
    }

    private fun showError(message: String) {
        binding.summaryContent.visibility = View.GONE
        binding.errorCard.visibility = View.VISIBLE
        binding.errorMessage.text = message
    }

    private fun statusBadgeDrawable(status: String): Int = when (status.lowercase()) {
        "running" -> R.drawable.badge_running
        "paused", "awaiting_input", "pending" -> R.drawable.badge_pending
        "stopped", "failed", "cancelled", "canceled" -> R.drawable.badge_timeout
        "completed", "done" -> R.drawable.badge_completed
        else -> R.drawable.badge_pending
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
