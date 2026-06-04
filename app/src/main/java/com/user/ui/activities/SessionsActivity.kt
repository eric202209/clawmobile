package com.user.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.user.ClawMobileApplication
import com.user.R
import com.user.data.GatewaySessionMapper
import com.user.data.MobileSessionListItem
import com.user.databinding.ActivitySessionsBinding
import com.user.service.OrchestratorApiClient
import com.user.ui.SessionAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionsBinding
    private lateinit var sessionAdapter: SessionAdapter
    private var orchestratorClient: OrchestratorApiClient? = null
    private var allSessions: List<MobileSessionListItem> = emptyList()
    private var currentStatusFilter: String? = null
    private var currentProjectFilter: String? = null
    private var currentPage = 1
    private val pageSize = 50
    private var loadedFromCache = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as ClawMobileApplication
        if (app.prefsManager.isOrchestratorConfigured()) {
            orchestratorClient = OrchestratorApiClient(
                app.prefsManager,
                app.prefsManager.gatewayToken
            )
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.nav_sessions)

        sessionAdapter = SessionAdapter { session ->
            startActivity(
                Intent(this, GatewaySessionSummaryActivity::class.java).apply {
                    putExtra("session_id", session.id.toString())
                    putExtra("session_name", session.name)
                }
            )
        }

        binding.sessionsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SessionsActivity)
            adapter = sessionAdapter
        }

        binding.statusFilterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentStatusFilter = when (checkedIds.firstOrNull()) {
                R.id.chipFilterRunning -> "running"
                R.id.chipFilterPaused -> "paused"
                R.id.chipFilterDone -> "done"
                R.id.chipFilterFailed -> "failed"
                else -> null
            }
            currentPage = 1
            loadSessions()
        }

        binding.offlineBanner.retryAction = { loadSessions() }
        binding.swipeRefresh.setOnRefreshListener {
            currentPage = 1
            loadSessions()
        }
        binding.loadMoreButton.setOnClickListener {
            currentPage++
            if (loadedFromCache) applyFilter() else loadSessions()
        }

        binding.fabStartSession.setOnClickListener {
            StartSessionBottomSheet().show(supportFragmentManager, "start_session")
        }

        loadCachedSessions()
        loadSessions()
        loadProjectFilterChips()
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
                    delay(30_000)
                    val hasActive = allSessions.any {
                        it.status.lowercase() in setOf("running", "pending")
                    }
                    if (hasActive || allSessions.isEmpty()) {
                        loadSessions()
                    }
                }
            }
        }
    }

    private fun loadProjectFilterChips() {
        val client = orchestratorClient ?: return
        lifecycleScope.launch {
            client.getProjects().onSuccess { projects ->
                if (projects.size < 2) return@onSuccess
                binding.projectFilterScroll.visibility = View.VISIBLE
                binding.projectFilterGroup.removeAllViews()

                val allChip = Chip(this@SessionsActivity).apply {
                    text = getString(R.string.sessions_filter_all_projects)
                    isCheckable = true
                    isChecked = true
                    tag = null as String?
                }
                allChip.setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        currentProjectFilter = null
                        currentPage = 1
                        loadSessions()
                    }
                }
                binding.projectFilterGroup.addView(allChip)

                projects.take(8).forEach { project ->
                    val chip = Chip(this@SessionsActivity).apply {
                        text = project.name.take(20)
                        isCheckable = true
                        tag = project.id.toString()
                    }
                    chip.setOnCheckedChangeListener { _, checked ->
                        if (checked) {
                            currentProjectFilter = project.id.toString()
                            currentPage = 1
                            loadSessions()
                        }
                    }
                    binding.projectFilterGroup.addView(chip)
                }
            }
        }
    }

    private fun loadSessions() {
        val client = orchestratorClient ?: run {
            binding.swipeRefresh.isRefreshing = false
            binding.offlineBanner.showWithTimestamp(null)
            return
        }
        lifecycleScope.launch {
            binding.swipeRefresh.isRefreshing = true
            client.listSessions(
                status = currentStatusFilter,
                projectId = currentProjectFilter,
                limit = pageSize,
                offset = (currentPage - 1) * pageSize
            )
                .onSuccess { sessions ->
                    loadedFromCache = false
                    allSessions = if (currentPage == 1) {
                        sessions
                    } else {
                        (allSessions + sessions).distinctBy { it.id }
                    }
                    applyFilter()
                    binding.offlineBanner.hide()
                    cacheSessions(sessions)
                    binding.loadMoreButton.visibility =
                        if (sessions.size == pageSize) View.VISIBLE else View.GONE
                }
                .onFailure {
                    loadCachedSessions(showBanner = true)
                }
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun loadCachedSessions(showBanner: Boolean = false) {
        val app = application as ClawMobileApplication
        lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) {
                app.gatewaySessionDao.getPage(500, 0)
            }
            if (cached.isNotEmpty()) {
                loadedFromCache = true
                allSessions = cached.map { GatewaySessionMapper.toApiListItem(it) }
                currentPage = 1
                applyFilter()
                val newestCache = cached.maxOfOrNull { it.cachedAt }
                if (showBanner) {
                    binding.offlineBanner.showWithTimestamp(newestCache)
                }
            } else if (showBanner) {
                binding.offlineBanner.showWithTimestamp(null)
            }
        }
    }

    private fun cacheSessions(sessions: List<MobileSessionListItem>) {
        val app = application as ClawMobileApplication
        val now = System.currentTimeMillis()
        val entities = sessions.map { GatewaySessionMapper.fromApi(it, now) }
        lifecycleScope.launch(Dispatchers.IO) {
            app.gatewaySessionDao.upsertAll(entities)
        }
    }

    private fun applyFilter() {
        val filtered = allSessions.filter { session ->
            (currentStatusFilter == null || session.status.lowercase() == currentStatusFilter) &&
                (currentProjectFilter == null || session.projectId.toString() == currentProjectFilter)
        }
        val totalFiltered = filtered.size
        val displayed = if (loadedFromCache) filtered.take(pageSize * currentPage) else filtered
        sessionAdapter.submitSessions(displayed)
        if (loadedFromCache) {
            binding.loadMoreButton.visibility =
                if (displayed.size < totalFiltered) View.VISIBLE else View.GONE
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
