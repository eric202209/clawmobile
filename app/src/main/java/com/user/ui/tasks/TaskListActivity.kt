package com.user.ui.tasks

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.user.ClawMobileApplication
import com.user.R
import com.user.data.DashboardSummary
import com.user.data.MobileSessionListItem
import com.user.data.RecentActivity
import com.user.data.OrchestTask
import com.user.data.PrefsManager
import com.user.data.Project
import com.user.data.Task
import com.user.data.TaskStatus
import com.user.databinding.ActivityTaskListBinding
import com.user.service.OrchestratorApiClient
import com.user.ui.CommandAssist
import com.user.ui.activities.SessionsActivity
import com.user.ui.tasks.ProjectProgressAdapter
import com.user.viewmodel.TaskViewModel
import com.user.viewmodel.TaskViewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Activity for displaying and managing tasks
 */
class TaskListActivity : AppCompatActivity() {

    private enum class TaskFilterMode {
        ALL,
        PENDING,
        ACTIVE,
        DONE,
        FAILED
    }

    private data class BlockerItem(
        val project: Project,
        val summary: String,
        val severity: Int,
    )

    private enum class ControlPlaneState {
        HEALTHY,
        DEGRADED,
        AUTH_ISSUE,
        NOT_CONFIGURED
    }

    private data class DiagnosticsSnapshot(
        val state: ControlPlaneState,
        val title: String,
        val details: String,
        val help: String,
    )

    private lateinit var binding: ActivityTaskListBinding
    private lateinit var taskAdapter: TaskAdapter
    private lateinit var viewModel: TaskViewModel
    private var orchestratorApiClient: OrchestratorApiClient? = null
    private lateinit var prefsManager: PrefsManager
    private lateinit var projectProgressAdapter: ProjectProgressAdapter
    private val projectStatsById = mutableMapOf<String, ProjectProgressAdapter.ProjectStats>()
    private var latestDashboardStats: DashboardSummary? = null
    private var latestRecentActivity: List<RecentActivity> = emptyList()
    private var lastDiagnosticsMessage: String? = null
    private var primaryBlockerProject: Project? = null
    private var latestTasks: List<Task> = emptyList()
    private var latestProjects: List<Project> = emptyList()
    private var latestSessions: List<MobileSessionListItem> = emptyList()
    private var currentTaskFilterMode: TaskFilterMode = TaskFilterMode.ALL
    private var latestDiagnosticsSnapshot: DiagnosticsSnapshot? = null
    private val allProjectTasks = mutableMapOf<String, Pair<String, List<OrchestTask>>>()
    private var groupedTaskAdapter: GroupedTaskAdapter? = null
    private val expandedProjectIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.task_hub_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.orchestratorStatsView.setOnClickListener {
            if (prefsManager.isOrchestratorConfigured().not() || lastDiagnosticsMessage != null) {
                startActivity(Intent(this, com.user.ui.activities.SettingsActivity::class.java))
            }
        }

        binding.blockersCard.setOnClickListener {
            primaryBlockerProject?.let { project ->
                openProject(project)
            }
        }
        binding.searchInput.addTextChangedListener {
            applyVisibleFilters()
        }
        binding.chipBrowseSessions.setOnClickListener { scrollToSection(binding.executionCard) }
        binding.chipBrowseProjects.setOnClickListener { scrollToSection(binding.projectsCard) }
        binding.chipBrowseTasks.setOnClickListener { scrollToSection(binding.tasksSectionHeader) }
        binding.latestFailureView.setOnClickListener { openLatestFailure() }
        binding.runningSessionView.setOnClickListener { openRunningSession() }
        binding.resumableSessionView.setOnClickListener { openResumableSession() }
        binding.openSessionsButton.setOnClickListener {
            startActivity(Intent(this, SessionsActivity::class.java))
        }

        setupStatusChips()
        setupRecyclerView()
        setupProjectRecyclerView()
        setupViewModel()
        setupObservers()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAll()
        loadProjectsFromOrchestrator()
    }

    private fun setupViewModel() {
        val sessionId = intent.getStringExtra("session_id") ?: ""
        val app = application as ClawMobileApplication
        prefsManager = app.prefsManager

        // Log configuration status for debugging
        Log.d("TaskListActivity", "Session ID: '$sessionId'")
        Log.d("TaskListActivity", "Orchestrator configured: ${prefsManager.isOrchestratorConfigured()}")
        if (prefsManager.isOrchestratorConfigured()) {
            Log.d("TaskListActivity", "Orchestrator URL: ${prefsManager.orchestratorServerUrl}")
            Log.d("TaskListActivity", "Gateway token length: ${prefsManager.gatewayToken.length}")
        }

        // Create Orchestrator API client if configured
        if (prefsManager.isOrchestratorConfigured()) {
            orchestratorApiClient = OrchestratorApiClient(
                prefs = prefsManager,
                gatewayToken = prefsManager.gatewayToken
            )
            Log.d("TaskListActivity", "OrchestratorApiClient created successfully")
        } else {
            Log.w("TaskListActivity", "Orchestrator not configured - will use local tasks only")
        }

        val factory = TaskViewModelFactory(
            repository = app.repository,
            sessionId = sessionId,
            orchestratorClient = orchestratorApiClient
        )
        viewModel = ViewModelProvider(this, factory)[TaskViewModel::class.java]
        renderDiagnosticsCard()
    }

    private fun setupStatusChips() {
        binding.taskStatusChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentTaskFilterMode = when (checkedIds.firstOrNull()) {
                R.id.chipPending -> TaskFilterMode.PENDING
                R.id.chipRunning -> TaskFilterMode.ACTIVE
                R.id.chipDone -> TaskFilterMode.DONE
                R.id.chipFailed -> TaskFilterMode.FAILED
                else -> TaskFilterMode.ALL
            }
            applyVisibleFilters()
        }
    }

    private fun setupRecyclerView() {
        taskAdapter = TaskAdapter(
            onApproveClick = { task -> approveTask(task) },
            onRejectClick = { task -> rejectTask(task) },
            onStartClick = { task -> startTask(task) },
            onViewClick = { task -> viewTaskDetails(task) },
            onLongPress = { task ->
                CommandAssist.showTaskActions(this, task.taskId, task.title)
            }
        )
        groupedTaskAdapter = GroupedTaskAdapter(
            onHeaderClick = { projectId ->
                if (!expandedProjectIds.add(projectId)) {
                    expandedProjectIds.remove(projectId)
                }
                buildGroupedList()
            },
            onTaskClick = { task -> viewOrchestTaskDetails(task) },
            onTaskLongPress = { task ->
                CommandAssist.showTaskActions(this, task.taskId, task.title)
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@TaskListActivity)
            adapter = taskAdapter
        }
    }

    private fun setupProjectRecyclerView() {
        projectProgressAdapter = ProjectProgressAdapter(
            onProjectClickListener = { project -> openProject(project) },
            onProjectPinClickListener = { project -> toggleProjectPin(project) },
            isProjectPinned = { project -> prefsManager.isProjectPinned(project.getProjectId()) },
            onProjectLongClickListener = { project ->
                CommandAssist.showProjectActions(this, project.getProjectId(), project.name)
            }
        )
        binding.projectsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@TaskListActivity)
            adapter = projectProgressAdapter
        }
    }

    private fun setupObservers() {
        Log.d("TaskListActivity", "Setting up observers for tasks")

        // Single observer for all tasks to avoid duplicate processing
        viewModel.allTasks.observe(this) { tasks ->
            Log.d("TaskListActivity", "Received ${tasks.size} tasks from ViewModel")
            latestTasks = tasks
            applyVisibleFilters()
            updateLocalStats(tasks)  // Update stats from local tasks as fallback

            // If no tasks loaded, show a helpful message about potential database access issues
            if (tasks.isEmpty()) {
                Log.d("TaskListActivity", "No tasks loaded - checking for database access issues")
                binding.emptyView.visibility = TextView.VISIBLE

                // Show empty state view with custom text if database might be inaccessible
                val sessionId = intent.getStringExtra("session_id") ?: ""
                val emptyText = when {
                    sessionId.isBlank() && orchestratorApiClient == null ->
                        "Orchestrator not configured. Please configure it in Settings."
                    sessionId.isBlank() ->
                        "No tasks found from Orchestrator. Check if the backend is running and accessible."
                    else -> "No tasks found for this session. Tasks may not have been created yet, or there could be a database access issue."
                }
                (binding.emptyView as TextView).text = emptyText
            }
        }

        viewModel.pendingTasks.observe(this) { tasks ->
            binding.pendingCountNew.text = tasks.size.toString()
            Log.d("TaskListActivity", "Pending tasks: ${tasks.size}")
        }

        viewModel.runningTasks.observe(this) { tasks ->
            // Update running count in the layout
            binding.runningCount.text = tasks.size.toString()
            Log.d("TaskListActivity", "Running/In-progress tasks: ${tasks.size}")
        }

        // Observe Orchestrator stats for comprehensive dashboard view
        viewModel.orchestratorStats.observe(this) { stats ->
            updateOrchestratorDashboard(stats)
        }

        viewModel.recentActivity.observe(this) { activity ->
            updateRecentActivity(activity)
        }

        viewModel.orchestratorWarning.observe(this) { warning ->
            updateOrchestratorWarning(warning)
        }

        // Load and display projects from Orchestrator with task counts
        loadProjectsFromOrchestrator()
    }

    private fun updateOrchestratorDashboard(stats: DashboardSummary?) {
        if (stats == null) return
        latestDashboardStats = stats
        lastDiagnosticsMessage = null

        // Update all dashboard stats
        binding.totalProjectsCount.text = stats.projects.toString()
        binding.totalTasksCount.text = stats.tasks.total.toString()
        val running = maxOf(stats.tasks.inProgress, stats.tasks.running)
        binding.runningCount.text = running.toString()

        // Calculate pending and completed from Orchestrator data
        val pending = stats.tasks.pending + stats.tasks.approved
        binding.pendingCountNew.text = pending.toString()
        binding.completedCount.text = stats.tasks.done.toString()

        // Show comprehensive stats summary
        binding.executionSummaryView.text = when {
            running > 0 && stats.tasks.failed > 0 ->
                "$running running • ${stats.tasks.failed} failed • ${stats.tasks.done} done"
            running > 0 ->
                "$running task(s) currently running"
            stats.tasks.failed > 0 && stats.tasks.done > 0 ->
                "${stats.tasks.failed} failed • ${stats.tasks.done} done"
            stats.tasks.failed > 0 -> "${stats.tasks.failed} failed task(s) need attention"
            pending > 0 -> "$pending task(s) waiting to run"
            stats.tasks.done > 0 -> "${stats.tasks.done} task(s) completed successfully"
            else -> "No execution activity yet"
        }

        renderDiagnosticsCard()
        binding.orchestratorStatsView.visibility = TextView.VISIBLE
        renderOverviewDetails()
    }

    private fun updateOrchestratorWarning(warning: String?) {
        lastDiagnosticsMessage = warning
        if (warning.isNullOrBlank()) {
            renderDiagnosticsCard()
            return
        }

        renderDiagnosticsCard()
        binding.orchestratorStatsView.visibility = TextView.VISIBLE
        binding.executionSummaryView.text = "Sync problem detected"
    }

    private fun updateRecentActivity(activity: List<RecentActivity>) {
        latestRecentActivity = activity
        renderOverviewDetails()
    }

    private fun updateLocalStats(tasks: List<Task>) {
        // Update counts based on local task data
        val totalTasks = tasks.size
        val running = tasks.count { it.status == TaskStatus.IN_PROGRESS || it.status == TaskStatus.APPROVED }
        val pending = tasks.count { it.status == TaskStatus.PENDING }
        val completed = tasks.count { it.status == TaskStatus.COMPLETED }

        binding.totalTasksCount.text = totalTasks.toString()
        if (running > 0) {
            binding.runningCount.text = running.toString()
        }
        if (pending > 0) {
            binding.pendingCountNew.text = pending.toString()
        }
        if (completed > 0) {
            binding.completedCount.text = completed.toString()
        }

    }

    private fun loadProjectsFromOrchestrator() {
        if (orchestratorApiClient == null || !prefsManager.isOrchestratorConfigured()) {
            Log.d("TaskListActivity", "Skipping Orchestrator projects - not configured")
            return
        }

        // Load projects in a coroutine - failures are handled silently
        CoroutineScope(Dispatchers.Main).launch {
            orchestratorApiClient?.getProjects()?.onSuccess { projects ->
                runOnUiThread {
                    Log.d("TaskListActivity", "Loaded ${projects.size} projects from Orchestrator")
                    latestProjects = sortProjectsForDisplay(projects)
                    applyVisibleFilters()
                    loadRecentSessions()

                    // Fetch task counts for each project after adapter is updated
                    loadProjectTaskCounts(latestProjects)
                }
            }?.onFailure { error ->
                Log.d("TaskListActivity", "Orchestrator projects unavailable: ${error.message}")
                runOnUiThread {
                    latestProjects = emptyList()
                    projectProgressAdapter.submitList(emptyList())
                    updateOrchestratorWarning(error.message ?: "Unable to load Orchestrator projects.")
                }
            }
        }
    }

    private fun loadRecentSessions() {
        CoroutineScope(Dispatchers.Main).launch {
            orchestratorApiClient?.listSessions()?.onSuccess { sessions ->
                latestSessions = sessions
                renderOverviewDetails()
            }?.onFailure {
                latestSessions = emptyList()
                renderOverviewDetails()
            }
        }
    }

    /**
     * Load task counts for each project by calling the status endpoint
     */
    private fun loadProjectTaskCounts(projects: List<Project>) {
        if (projects.isEmpty()) return

        CoroutineScope(Dispatchers.Main).launch {
            projects.forEach { project ->
                val projectId = project.getProjectId()

                // Call /mobile/projects/{project_id}/status to get task counts
                orchestratorApiClient?.getProjectStatus(projectId)?.onSuccess { status ->
                    runOnUiThread {
                        val position = projectProgressAdapter.currentList.indexOfFirst {
                            it.getProjectId() == projectId
                        }

                        if (position >= 0) {
                            status.tasks?.let { tasks ->
                                Log.d("TaskListActivity", "Loaded stats for project $projectId: $tasks")
                                val statsData = ProjectProgressAdapter.ProjectStats(
                                    running = tasks.running,
                                    pending = tasks.pending,
                                    completed = tasks.done,
                                    total = tasks.total,
                                    failed = tasks.failed,
                                    activeSessions = status.activeSessions
                                )
                                projectStatsById[projectId] = statsData
                                projectProgressAdapter.updateProjectStats(projectId, statsData)
                            } ?: run {
                                val statsData = ProjectProgressAdapter.ProjectStats()
                                projectStatsById[projectId] = statsData
                                projectProgressAdapter.updateProjectStats(projectId, statsData)
                            }
                            renderOverviewDetails()
                        }
                    }
                }?.onFailure { error ->
                    Log.d("TaskListActivity", "Orchestrator status unavailable for project $projectId: ${error.message}")
                    runOnUiThread {
                        val statsData = ProjectProgressAdapter.ProjectStats()
                        projectStatsById[projectId] = statsData
                        projectProgressAdapter.updateProjectStats(projectId, statsData)
                        renderOverviewDetails()
                    }
                }

                orchestratorApiClient?.getProjectTasks(projectId)?.onSuccess { tasks ->
                    allProjectTasks[projectId] = Pair(project.name, tasks)
                    buildGroupedList()
                }
            }
        }
    }

    private fun buildDiagnosticsSnapshot(): DiagnosticsSnapshot {
        val gatewayConfigured = prefsManager.gatewayToken.isNotBlank()
        val orchestratorConfigured = prefsManager.isOrchestratorConfigured()
        val activeSessions = latestDashboardStats?.sessions?.active ?: 0
        val issue = lastDiagnosticsMessage?.trim()

        if (!orchestratorConfigured) {
            return DiagnosticsSnapshot(
                state = ControlPlaneState.NOT_CONFIGURED,
                title = "Setup needed",
                details = "Connect ClawMobile to Gateway and Orchestrator in Settings.",
                help = "You can still browse local tasks, search, and use pinned items.\nLive project updates and recovery actions will appear after setup."
            )
        }

        if (!gatewayConfigured) {
            return DiagnosticsSnapshot(
                state = ControlPlaneState.NOT_CONFIGURED,
                title = "Gateway token missing",
                details = "Add the shared token in Settings to enable live control.",
                help = "You can still browse saved task data.\nCommands, session actions, and live sync need the shared token."
            )
        }

        val isAuthIssue = issue?.contains("401", ignoreCase = true) == true ||
                issue?.contains("unauthorized", ignoreCase = true) == true ||
                issue?.contains("invalid", ignoreCase = true) == true ||
                issue?.contains("auth", ignoreCase = true) == true

        if (isAuthIssue) {
            return DiagnosticsSnapshot(
                state = ControlPlaneState.AUTH_ISSUE,
                title = "Sign-in needs attention",
                details = "ClawMobile could reach Orchestrator, but the shared key was rejected.\n${issue?.let { "Issue: $it" } ?: "Update the key in Settings and try again."}",
                help = "You can still browse local task data and search.\nLive refresh and session actions will return after the key matches again."
            )
        }

        if (latestDashboardStats == null) {
            return DiagnosticsSnapshot(
                state = ControlPlaneState.DEGRADED,
                title = "Live updates unavailable",
                details = "ClawMobile can still show saved task data, but it cannot refresh Orchestrator right now.${issue?.let { "\nIssue: $it" } ?: ""}",
                help = "You can still browse tasks, search, and open existing details.\nLive project updates, recent run cards, and remote recovery will return when the connection comes back."
            )
        }

        return DiagnosticsSnapshot(
            state = ControlPlaneState.HEALTHY,
            title = "Connected",
            details = "Live project status and recovery actions are available.\nActive sessions: $activeSessions",
            help = "Live project status, blockers, recent execution, and recovery actions are available."
        )
    }

    private fun renderDiagnosticsCard() {
        val snapshot = buildDiagnosticsSnapshot()
        latestDiagnosticsSnapshot = snapshot
        binding.orchestratorStatsView.text = "${snapshot.title}\n${snapshot.details}"
        binding.orchestratorStatsView.visibility = TextView.VISIBLE
        binding.orchestratorHelpView.text = snapshot.help
        binding.orchestratorHelpView.visibility =
            if (snapshot.state == ControlPlaneState.HEALTHY) View.GONE else View.VISIBLE
        binding.orchestratorStatsView.setBackgroundColor(
            ContextCompat.getColor(
                this,
                when (snapshot.state) {
                    ControlPlaneState.HEALTHY -> R.color.success_surface
                    ControlPlaneState.DEGRADED -> R.color.surface_container_high
                    ControlPlaneState.AUTH_ISSUE -> R.color.error_surface
                    ControlPlaneState.NOT_CONFIGURED -> R.color.warning_surface
                }
            )
        )
        binding.orchestratorStatsView.setTextColor(
            ContextCompat.getColor(
                this,
                when (snapshot.state) {
                    ControlPlaneState.HEALTHY -> R.color.text_primary
                    ControlPlaneState.DEGRADED -> R.color.text_primary
                    ControlPlaneState.AUTH_ISSUE -> R.color.white
                    ControlPlaneState.NOT_CONFIGURED -> R.color.text_primary
                }
            )
        )
        binding.orchestratorStatsView.isClickable =
            snapshot.state != ControlPlaneState.HEALTHY
    }

    private fun renderOverviewDetails() {
        val diagnostics = latestDiagnosticsSnapshot ?: buildDiagnosticsSnapshot()
        val blockers = projectProgressAdapter.currentList.mapNotNull { project ->
            val stats = projectStatsById[project.getProjectId()] ?: return@mapNotNull null
            when {
                stats.failed > 0 -> BlockerItem(
                    project = project,
                    summary = "${project.name}: ${stats.failed} failed • ${stats.activeSessions} active session${if (stats.activeSessions == 1) "" else "s"}",
                    severity = 0,
                )
                stats.pending > 0 -> BlockerItem(
                    project = project,
                    summary = "${project.name}: ${stats.pending} waiting",
                    severity = 1,
                )
                else -> null
            }
        }.sortedBy { it.severity }

        primaryBlockerProject = blockers.firstOrNull()?.project
        val canOpenBlocker = primaryBlockerProject != null && diagnostics.state == ControlPlaneState.HEALTHY
        binding.blockersCard.isClickable = canOpenBlocker
        binding.blockersCard.isFocusable = canOpenBlocker
        val showBlockersCard =
            diagnostics.state != ControlPlaneState.HEALTHY || blockers.isNotEmpty()
        binding.blockersCard.visibility = if (showBlockersCard) View.VISIBLE else View.GONE

        binding.blockerSummaryView.text = when {
            diagnostics.state != ControlPlaneState.HEALTHY -> "Blocker check unavailable"
            blockers.any { it.severity == 0 } -> "Needs attention now"
            blockers.any { it.severity == 1 } -> "Waiting on next step"
            else -> "Nothing urgent right now"
        }
        binding.blockerDetailsView.text = when {
            diagnostics.state != ControlPlaneState.HEALTHY ->
                "ClawMobile cannot check blockers right now.\nYou can still browse tasks or reconnect in Settings."
            blockers.isNotEmpty() -> buildString {
                append(blockers.take(3).joinToString("\n") { it.summary })
                primaryBlockerProject?.let {
                    append("\n\nTap this card to open ")
                    append(it.name)
                }
            }
            else -> "Failed work or waiting projects will appear here."
        }

        val recentActivityText = when {
            diagnostics.state == ControlPlaneState.NOT_CONFIGURED ->
                "Finish setup first. Once Orchestrator is connected, this area will show failures, live sessions, and resumable work."
            diagnostics.state == ControlPlaneState.AUTH_ISSUE ->
                "The shared mobile key was rejected. Fix the key in Settings, then refresh to restore live execution visibility."
            diagnostics.state == ControlPlaneState.DEGRADED ->
                "Remote execution details are temporarily unavailable. You can still browse local tasks while the backend or network path recovers."
            latestRecentActivity.isNotEmpty() -> latestRecentActivity.take(3).joinToString("\n\n") { activity ->
                "[${activity.level}] ${activity.message}"
            }
            latestProjects.isNotEmpty() ->
                "Open a project to inspect logs, status, checkpoints, and recovery actions."
            else -> "Create or sync a project to see live execution details here."
        }
        val latestFailure = latestSessions.firstOrNull { session ->
            session.status.equals("failed", true) || session.status.equals("stopped", true)
        }
        val showUnavailableOverviewCards = diagnostics.state != ControlPlaneState.HEALTHY
        val runningSession = latestSessions.firstOrNull { session ->
            session.status.equals("running", true) || session.isActive
        }
        val resumableSession = latestSessions.firstOrNull { session ->
            session.status.equals("paused", true) || session.status.equals("stopped", true)
        }
        val showRecentActivityText = when {
            diagnostics.state != ControlPlaneState.HEALTHY -> true
            latestRecentActivity.isNotEmpty() -> true
            latestFailure != null -> false
            runningSession != null || resumableSession != null -> false
            else -> true
        }
        binding.recentActivityView.text = recentActivityText
        binding.recentActivityView.visibility = if (showRecentActivityText) View.VISIBLE else View.GONE

        renderOverviewCard(
            textView = binding.latestFailureView,
            title = when {
                showUnavailableOverviewCards -> "Latest failure unavailable"
                latestFailure != null -> "Latest failure: ${latestFailure.name}"
                else -> ""
            },
            details = when {
                showUnavailableOverviewCards ->
                    "Restore connection or auth to inspect the newest failed run."
                latestFailure != null ->
                    "#${latestFailure.id} • ${latestFailure.status}"
                else -> ""
            },
            visible = showUnavailableOverviewCards || latestFailure != null,
            enabled = diagnostics.state == ControlPlaneState.HEALTHY && latestFailure != null
        )

        renderOverviewCard(
            textView = binding.runningSessionView,
            title = when {
                showUnavailableOverviewCards -> "Live session unavailable"
                runningSession != null -> "Running now: ${runningSession.name}"
                else -> "No active session"
            },
            details = when {
                showUnavailableOverviewCards ->
                    "Local browsing still works, but live session monitoring is paused."
                runningSession != null ->
                    "#${runningSession.id} • tap to open"
                else -> "Nothing is actively running right now."
            },
            visible = showUnavailableOverviewCards || runningSession != null || latestProjects.isNotEmpty(),
            enabled = diagnostics.state == ControlPlaneState.HEALTHY && runningSession != null
        )

        renderOverviewCard(
            textView = binding.resumableSessionView,
            title = when {
                showUnavailableOverviewCards -> "Resumable work unavailable"
                resumableSession != null -> "Best resumable: ${resumableSession.name}"
                else -> ""
            },
            details = when {
                showUnavailableOverviewCards ->
                    "Reconnect to check which stopped run has the safest checkpoint."
                resumableSession != null ->
                    "#${resumableSession.id} • ${resumableSession.status}"
                else -> ""
            },
            visible = showUnavailableOverviewCards || resumableSession != null,
            enabled = diagnostics.state == ControlPlaneState.HEALTHY && resumableSession != null
        )
    }

    private fun renderOverviewCard(
        textView: TextView,
        title: String,
        details: String,
        visible: Boolean,
        enabled: Boolean,
    ) {
        textView.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            return
        }

        textView.text = if (details.isBlank()) {
            title
        } else {
            "$title\n$details"
        }
        textView.alpha = if (enabled) 1f else 0.9f
        textView.isClickable = enabled
        textView.isFocusable = enabled
    }

    private fun openLatestFailure() {
        val session = latestSessions.firstOrNull {
            it.status.equals("failed", true) || it.status.equals("stopped", true)
        } ?: return
        openSession(session)
    }

    private fun openRunningSession() {
        val session = latestSessions.firstOrNull {
            it.status.equals("running", true) || it.isActive
        } ?: return
        openSession(session)
    }

    private fun openResumableSession() {
        val session = latestSessions.firstOrNull {
            it.status.equals("paused", true) || it.status.equals("stopped", true)
        } ?: return
        openSession(session)
    }

    private fun openSession(session: MobileSessionListItem) {
        startActivity(
            Intent(this, SessionDetailActivity::class.java).apply {
                putExtra("session_id", session.id.toString())
                putExtra("session_name", session.name)
            }
        )
    }

    private fun openProject(project: Project) {
        val intent = Intent(this, ProjectDetailActivity::class.java).apply {
            putExtra("project_id", project.getProjectId())
            putExtra("project_name", project.name)
        }
        startActivity(intent)
    }

    private fun toggleProjectPin(project: Project) {
        val pinned = prefsManager.togglePinnedProject(project.getProjectId())
        latestProjects = sortProjectsForDisplay(latestProjects)
        applyVisibleFilters()
        Snackbar.make(
            binding.root,
            if (pinned) R.string.project_pinned else R.string.project_unpinned,
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun dashboardSortRank(status: TaskStatus): Int {
        return when (status) {
            TaskStatus.PENDING -> 0
            TaskStatus.IN_PROGRESS -> 1
            TaskStatus.APPROVED -> 2
            TaskStatus.COMPLETED -> 3
            TaskStatus.FAILED -> 4
            TaskStatus.REJECTED -> 5
            TaskStatus.TIMEOUT -> 6
        }
    }

    private fun submitTasks(tasks: List<Task>) {
        taskAdapter.submitList(
            tasks.sortedWith(
                compareBy<Task> { dashboardSortRank(it.status) }
                    .thenByDescending { it.priority }
                    .thenByDescending { it.createdAt }
            )
        )
    }

    private fun updateEmptyState(tasks: List<Task>) {
        val hasTasks = tasks.isNotEmpty()
        binding.emptyView.visibility = if (hasTasks) View.GONE else View.VISIBLE
        binding.recyclerView.visibility = if (hasTasks) View.VISIBLE else View.GONE
    }

    private fun scrollToSection(target: View) {
        val topInset = resources.getDimensionPixelSize(R.dimen.space_md)
        binding.taskListScrollView.post {
            binding.taskListScrollView.smoothScrollTo(0, (target.top - topInset).coerceAtLeast(0))
        }
    }

    private fun updateSequenceIndicator(visible: Int, total: Int) {
        if (total > 0 && binding.recyclerView.visibility == View.VISIBLE) {
            binding.sequenceIndicator.text = "$visible of $total"
            binding.sequenceIndicator.visibility = View.VISIBLE
        } else {
            binding.sequenceIndicator.visibility = View.GONE
        }
    }

    private fun applyVisibleFilters() {
        val query = binding.searchInput.text?.toString()?.trim()?.lowercase().orEmpty()

        val visibleProjects = if (query.isBlank()) {
            latestProjects
        } else {
            latestProjects.filter { project ->
                project.name.lowercase().contains(query) ||
                        (project.description?.lowercase()?.contains(query) == true) ||
                        project.getProjectId().lowercase().contains(query)
            }
        }
        projectProgressAdapter.submitList(visibleProjects)
        renderOverviewDetails()

        if (allProjectTasks.isNotEmpty()) {
            buildGroupedList()
            return
        }

        val tasksForMode = when (currentTaskFilterMode) {
            TaskFilterMode.ALL -> latestTasks
            TaskFilterMode.PENDING -> latestTasks.filter { it.status == TaskStatus.PENDING }
            TaskFilterMode.ACTIVE -> latestTasks.filter {
                it.status == TaskStatus.IN_PROGRESS || it.status == TaskStatus.APPROVED
            }
            TaskFilterMode.DONE -> latestTasks.filter { it.status == TaskStatus.COMPLETED }
            TaskFilterMode.FAILED -> latestTasks.filter {
                it.status == TaskStatus.FAILED || it.status == TaskStatus.REJECTED || it.status == TaskStatus.TIMEOUT
            }
        }

        val visibleTasks = if (query.isBlank()) {
            tasksForMode
        } else {
            tasksForMode.filter { task ->
                task.title.lowercase().contains(query) ||
                        task.description.lowercase().contains(query) ||
                        task.status.name.lowercase().contains(query)
            }
        }
        submitTasks(visibleTasks)
        updateEmptyState(visibleTasks)
        updateSequenceIndicator(visibleTasks.size, latestTasks.size)
    }

    private fun sortProjectsForDisplay(projects: List<Project>): List<Project> {
        return projects.sortedWith(
            compareByDescending<Project> { prefsManager.isProjectPinned(it.getProjectId()) }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun buildGroupedList() {
        val adapter = groupedTaskAdapter ?: return
        val query = binding.searchInput.text?.toString()?.trim()?.lowercase().orEmpty()
        val items = mutableListOf<GroupedTaskAdapter.ListItem>()

        for (project in latestProjects) {
            val projectId = project.getProjectId()
            val (_, tasks) = allProjectTasks[projectId] ?: continue

            val filtered = tasks.filter { task ->
                val status = resolveOrchestTaskStatus(task)
                val matchesFilter = when (currentTaskFilterMode) {
                    TaskFilterMode.ALL -> true
                    TaskFilterMode.PENDING -> status == "pending"
                    TaskFilterMode.ACTIVE -> status in setOf("in_progress", "running", "executing", "approved")
                    TaskFilterMode.DONE -> status in setOf("completed", "done", "success")
                    TaskFilterMode.FAILED -> status in setOf("failed", "timeout", "rejected")
                }
                val matchesQuery = query.isBlank() ||
                        task.title.lowercase().contains(query) ||
                        task.description?.lowercase()?.contains(query) == true
                matchesFilter && matchesQuery
            }

            if (filtered.isNotEmpty()) {
                val isExpanded = projectId in expandedProjectIds
                items.add(GroupedTaskAdapter.ListItem.Header(projectId, project.name, filtered.size, isExpanded))
                if (isExpanded) {
                    filtered.forEach { items.add(GroupedTaskAdapter.ListItem.Task(it)) }
                }
            }
        }

        if (binding.recyclerView.adapter !== adapter) {
            binding.recyclerView.adapter = adapter
        }
        adapter.submitList(items)

        val hasTasks = items.isNotEmpty()
        binding.emptyView.visibility = if (hasTasks) View.GONE else View.VISIBLE
        binding.recyclerView.visibility = View.VISIBLE
        binding.sequenceIndicator.visibility = View.GONE
    }

    private fun resolveOrchestTaskStatus(task: OrchestTask) = when {
        task.status == "unknown" && task.hasActiveSession -> "running"
        task.status == "unknown" -> task.sessionStatus?.lowercase() ?: "pending"
        else -> task.status.lowercase()
    }

    private fun viewOrchestTaskDetails(task: OrchestTask) {
        startActivity(Intent(this, TaskDetailActivity::class.java).apply {
            putExtra("task_id", task.taskId)
        })
    }

    private fun approveTask(task: Task) {
        viewModel.approveTask(task.taskId)
    }

    private fun rejectTask(task: Task) {
        AlertDialog.Builder(this)
            .setTitle("Reject Task")
            .setMessage("Enter reason for rejection (optional):")
            .setPositiveButton("Reject") { _, _ ->
                viewModel.rejectTask(task.taskId, null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startTask(task: Task) {
        viewModel.startTask(task.taskId)
    }

    private fun viewTaskDetails(task: Task) {
        val intent = Intent(this, TaskDetailActivity::class.java).apply {
            putExtra("task_id", task.taskId)
            putExtra("session_id", this@TaskListActivity.intent.getStringExtra("session_id"))
        }
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.task_list_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_refresh -> {
                val usingOrchestrator = orchestratorApiClient != null && prefsManager.isOrchestratorConfigured()

                if (usingOrchestrator) {
                    // Refresh from Orchestrator API
                    viewModel.refreshAll()
                    Snackbar.make(binding.root, "Syncing with Orchestrator...", Snackbar.LENGTH_SHORT).show()
                } else {
                    // Just refresh local data
                    viewModel.loadTasks()
                    Snackbar.make(binding.root, "Refreshing tasks (local)...", Snackbar.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
