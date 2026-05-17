package com.user.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.user.ClawMobileApplication
import com.user.R
import com.user.data.MobileSessionListItem
import com.user.databinding.ActivitySessionsBinding
import com.user.service.OrchestratorApiClient
import com.user.ui.SessionAdapter
import com.user.ui.tasks.SessionDetailActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SessionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionsBinding
    private lateinit var sessionAdapter: SessionAdapter
    private var orchestratorClient: OrchestratorApiClient? = null
    private var allSessions: List<MobileSessionListItem> = emptyList()
    private var currentFilter: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as ClawMobileApplication
        if (app.prefsManager.isOrchestratorConfigured()) {
            orchestratorClient = OrchestratorApiClient(app.prefsManager, app.prefsManager.gatewayToken)
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.nav_sessions)

        sessionAdapter = SessionAdapter { session ->
            val intent = Intent(this, SessionDetailActivity::class.java).apply {
                putExtra("session_id", session.id.toString())
                putExtra("session_name", session.name)
            }
            startActivity(intent)
        }

        binding.sessionsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SessionsActivity)
            adapter = sessionAdapter
        }

        binding.statusFilterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when (checkedIds.firstOrNull()) {
                R.id.chipFilterRunning -> "running"
                R.id.chipFilterPaused -> "paused"
                R.id.chipFilterDone -> "done"
                R.id.chipFilterFailed -> "failed"
                else -> null
            }
            applyFilter()
        }

        binding.offlineBanner.retryAction = { loadSessions() }

        binding.fabStartSession.setOnClickListener {
            StartSessionBottomSheet().show(supportFragmentManager, "start_session")
        }

        loadSessions()
        startAutoPoll()
    }

    override fun onResume() {
        super.onResume()
        loadSessions()
    }

    private fun startAutoPoll() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(20_000)
                    val hasActive = allSessions.any {
                        it.status.lowercase() == "running" || it.status.lowercase() == "pending"
                    }
                    if (hasActive || allSessions.isEmpty()) {
                        loadSessions()
                    }
                }
            }
        }
    }

    private fun loadSessions() {
        val client = orchestratorClient ?: run {
            binding.offlineBanner.showWithTimestamp(null)
            return
        }
        lifecycleScope.launch {
            client.listSessions(currentFilter).onSuccess { sessions ->
                allSessions = sessions
                applyFilter()
                binding.offlineBanner.hide()
            }.onFailure {
                binding.offlineBanner.showWithTimestamp(null)
            }
        }
    }

    private fun applyFilter() {
        val filtered = if (currentFilter != null) {
            allSessions.filter { it.status.lowercase() == currentFilter }
        } else {
            allSessions
        }
        sessionAdapter.submitSessions(filtered)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
