package com.user.ui.tasks

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.user.ClawMobileApplication
import com.user.R
import com.user.data.OrchestTask
import com.user.data.Project
import com.user.data.ProjectSessionSummary
import com.user.databinding.ActivityProjectDetailBinding
import com.user.service.OrchestratorApiClient
import com.user.ui.CommandAssist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ProjectDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProjectDetailBinding
    private lateinit var adapter: ProjectTaskAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var orchestratorClient: OrchestratorApiClient? = null
    private var projectId: String = ""
    private var projectName: String = ""
    private var latestProjectSessions: List<ProjectSessionSummary> = emptyList()
    private var currentTasks: MutableList<OrchestTask> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectId = intent.getStringExtra("project_id") ?: ""
        projectName = intent.getStringExtra("project_name") ?: "Project"

        val app = application as ClawMobileApplication
        if (app.prefsManager.isOrchestratorConfigured()) {
            orchestratorClient = OrchestratorApiClient(
                prefs = app.prefsManager,
                gatewayToken = app.prefsManager.gatewayToken
            )
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = if (projectId.isBlank()) getString(R.string.nav_projects) else projectName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.filesAccordionHeader.setOnClickListener {
            toggleAccordion(binding.filesContent, binding.filesChevron)
        }

        setupTaskList()
        if (projectId.isBlank()) {
            promptForProject()
        } else {
            loadProjectData()
        }
    }

    private fun setupTaskList() {
        adapter = ProjectTaskAdapter(
            onTaskClick = { task ->
                val intent = Intent(this, TaskDetailActivity::class.java).apply {
                    putExtra("task_id", task.taskId)
                    putExtra("session_id", "")
                }
                startActivity(intent)
            },
            onTaskLongPress = { task ->
                CommandAssist.showTaskActions(this, task.taskId, task.title)
            },
            onDragStart = { viewHolder -> itemTouchHelper.startDrag(viewHolder) }
        )

        binding.projectTasksRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.projectTasksRecyclerView.adapter = adapter

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                val task = currentTasks.getOrNull(from) ?: return false
                if (task.status.lowercase() != "pending") return false
                currentTasks.add(to, currentTasks.removeAt(from))
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                val pos = viewHolder.adapterPosition
                val task = currentTasks.getOrNull(pos) ?: return
                val client = orchestratorClient ?: return
                CoroutineScope(Dispatchers.Main).launch {
                    client.updateTaskPosition(task.taskId, pos).onFailure {
                        Snackbar.make(binding.root, "Reorder failed — refreshing", Snackbar.LENGTH_SHORT).show()
                        loadProjectData()
                    }
                }
            }

            override fun isLongPressDragEnabled() = false
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(binding.projectTasksRecyclerView)
    }

    private fun loadProjectData() {
        val client = orchestratorClient ?: run {
            renderProjectSelectionState(getString(R.string.project_picker_missing_config))
            return
        }

        binding.projectTasksRecyclerView.visibility = View.VISIBLE
        binding.filesCard.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.Main).launch {
            client.getProjectStatus(projectId).onSuccess { status ->
                binding.projectStatusSummary.text = when {
                    status.tasks == null -> "No execution data yet"
                    status.tasks.failed > 0 ->
                        "${status.tasks.failed} failed • ${status.tasks.done} done"
                    status.tasks.running > 0 ->
                        "${status.tasks.running} running • ${status.tasks.pending} waiting"
                    status.tasks.total > 0 ->
                        "${status.tasks.done} done • ${status.tasks.pending} waiting • ${status.tasks.total} total"
                    else -> "No tasks yet"
                }

                val description = status.description?.takeIf { it.isNotBlank() }
                if (description != null) {
                    binding.projectDescription.text = description
                    binding.projectDescription.visibility = View.VISIBLE
                } else {
                    binding.projectDescription.visibility = View.GONE
                }

                val rules = status.projectRules?.takeIf { it.isNotBlank() }
                if (rules != null) {
                    val firstThreeLines = rules.lines().filter { it.isNotBlank() }.take(3).joinToString("\n")
                    binding.projectRulesText.text = firstThreeLines
                    binding.projectRulesText.visibility = View.VISIBLE
                } else {
                    binding.projectRulesText.visibility = View.GONE
                }

                val activeSessionSummary = if (status.sessions.isEmpty()) {
                    null
                } else {
                    buildString {
                        append("${status.activeSessions} active")
                        if (status.recentSessions > 0) {
                            append(" • ${status.recentSessions} recent")
                        }
                        append(" session")
                        if (status.recentSessions != 1 || status.activeSessions != 1) {
                            append("s")
                        }
                    }
                }
                binding.sessionsSection.visibility =
                    if (status.sessions.isEmpty()) View.GONE else View.VISIBLE
                binding.activeSessionsSummary.text = activeSessionSummary.orEmpty()
                latestProjectSessions = status.sessions
                renderPrimaryAction(status.sessions)
                renderActiveSessions(status.sessions)
            }.onFailure { error ->
                binding.projectStatusSummary.text = error.message ?: "Unable to load project status."
                binding.projectPrimaryActionView.visibility = View.GONE
                binding.sessionsSection.visibility = View.GONE
                binding.activeSessionsContainer.removeAllViews()
            }

            client.getProjectTree(projectId).onSuccess { tree ->
                val baseline = tree.baseline
                if (baseline != null) {
                    binding.baselineSummaryText.text = if (baseline.exists) {
                        "Baseline: ${baseline.fileCount} files from ${baseline.promotedTaskCount} promoted task(s)"
                    } else {
                        "No canonical baseline yet"
                    }
                    binding.baselineSummaryText.visibility = View.VISIBLE
                } else {
                    binding.baselineSummaryText.visibility = View.GONE
                }

                when {
                    !tree.exists -> {
                        binding.projectTreeSummary.text = "Project workspace has not been created yet."
                        binding.filesCard.visibility = View.GONE
                        binding.projectTreeView.visibility = View.GONE
                    }
                    tree.treeLines.isEmpty() -> {
                        binding.projectTreeSummary.text = "Project workspace is empty."
                        binding.filesCard.visibility = View.VISIBLE
                        binding.projectTreeView.visibility = View.GONE
                    }
                    else -> {
                        val summary = buildString {
                            append("${tree.totalEntriesShown} item")
                            if (tree.totalEntriesShown != 1) append("s")
                            append(" shown")
                            if (tree.truncated) append(" • trimmed for mobile")
                        }
                        binding.filesCard.visibility = View.VISIBLE
                        binding.projectTreeSummary.text = summary
                        binding.projectTreeView.text = tree.treeLines.joinToString("\n")
                        binding.projectTreeView.visibility = View.VISIBLE
                    }
                }
            }.onFailure { error ->
                binding.filesCard.visibility = View.VISIBLE
                binding.baselineSummaryText.visibility = View.GONE
                binding.projectTreeSummary.text = error.message ?: "Unable to load file tree."
                binding.projectTreeView.visibility = View.GONE
            }

            client.getProjectTasks(projectId).onSuccess { tasks ->
                val sorted = tasks.sortedWith(
                    compareBy<OrchestTask> {
                        it.sequenceIndex ?: Int.MAX_VALUE
                    }.thenBy { it.title.lowercase() }
                )
                currentTasks = sorted.toMutableList()
                adapter.submitList(sorted)
                if (latestProjectSessions.isEmpty()) {
                    renderPrimaryAction(emptyList(), sorted)
                }
                binding.projectTasksEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
                updateTaskProgress(sorted)
            }.onFailure { error ->
                binding.projectTasksEmpty.visibility = View.VISIBLE
                binding.projectTasksEmpty.text = error.message ?: "Unable to load project tasks."
            }
        }
    }

    private fun promptForProject() {
        val client = orchestratorClient ?: run {
            renderProjectSelectionState(getString(R.string.project_picker_missing_config))
            return
        }

        renderProjectSelectionState(getString(R.string.project_picker_loading))
        CoroutineScope(Dispatchers.Main).launch {
            client.getProjects().onSuccess { projects ->
                when {
                    projects.isEmpty() -> renderProjectSelectionState(getString(R.string.project_picker_empty))
                    projects.size == 1 -> selectProject(projects.first())
                    else -> showProjectPicker(projects)
                }
            }.onFailure { error ->
                renderProjectSelectionState(
                    error.message ?: getString(R.string.project_picker_error)
                )
            }
        }
    }

    private fun showProjectPicker(projects: List<Project>) {
        supportActionBar?.title = getString(R.string.project_picker_title)
        AlertDialog.Builder(this)
            .setTitle(R.string.project_picker_title)
            .setItems(projects.map { it.name }.toTypedArray()) { _, which ->
                selectProject(projects[which])
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun selectProject(project: Project) {
        projectId = project.getProjectId()
        projectName = project.name
        supportActionBar?.title = projectName
        binding.projectTasksRecyclerView.visibility = View.VISIBLE
        loadProjectData()
    }

    private fun renderProjectSelectionState(message: String) {
        binding.projectStatusSummary.text = message
        binding.projectDescription.visibility = View.GONE
        binding.projectPrimaryActionView.visibility = View.GONE
        binding.sessionsSection.visibility = View.GONE
        binding.activeSessionsContainer.removeAllViews()
        binding.taskProgressIndicator.visibility = View.GONE
        binding.taskProgressText.visibility = View.GONE
        binding.projectTasksRecyclerView.visibility = View.GONE
        binding.projectTasksEmpty.visibility = View.VISIBLE
        binding.projectTasksEmpty.text = message
        binding.filesCard.visibility = View.VISIBLE
        binding.projectTreeSummary.text = message
        binding.projectTreeView.visibility = View.GONE
    }

    private fun updateTaskProgress(tasks: List<OrchestTask>) {
        val total = tasks.size
        val done = tasks.count { it.status.lowercase().let { s -> s == "done" || s == "completed" || s == "success" } }
        if (total > 0) {
            binding.taskProgressIndicator.max = total
            binding.taskProgressIndicator.progress = done
            binding.taskProgressIndicator.visibility = View.VISIBLE
            binding.taskProgressText.text = "$done/$total done"
            binding.taskProgressText.visibility = View.VISIBLE
        } else {
            binding.taskProgressIndicator.visibility = View.GONE
            binding.taskProgressText.visibility = View.GONE
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

    private fun renderPrimaryAction(
        sessions: List<ProjectSessionSummary>,
        tasks: List<OrchestTask> = adapter.currentList
    ) {
        val liveSession = sessions.firstOrNull { it.isActive || it.status.equals("running", true) }
        val resumableSession = sessions.firstOrNull {
            it.status.equals("paused", true) || it.status.equals("stopped", true)
        }
        val nextTask = tasks.firstOrNull {
            val normalized = it.status.lowercase()
            normalized == "running" || normalized == "in_progress" || normalized == "pending" || normalized == "approved"
        }

        when {
            liveSession != null -> {
                binding.projectPrimaryActionView.visibility = View.VISIBLE
                binding.projectPrimaryActionView.text =
                    "${getString(R.string.project_primary_action_live)}\n${liveSession.name} • #${liveSession.id}"
                binding.projectPrimaryActionView.isClickable = true
                binding.projectPrimaryActionView.isFocusable = true
                binding.projectPrimaryActionView.setOnClickListener {
                    openSession(liveSession.id.toString(), liveSession.name)
                }
            }
            resumableSession != null -> {
                binding.projectPrimaryActionView.visibility = View.VISIBLE
                binding.projectPrimaryActionView.text =
                    "${getString(R.string.project_primary_action_resume)}\n${resumableSession.name} • #${resumableSession.id}"
                binding.projectPrimaryActionView.isClickable = true
                binding.projectPrimaryActionView.isFocusable = true
                binding.projectPrimaryActionView.setOnClickListener {
                    openSession(resumableSession.id.toString(), resumableSession.name)
                }
            }
            nextTask != null -> {
                binding.projectPrimaryActionView.visibility = View.VISIBLE
                binding.projectPrimaryActionView.text =
                    "${getString(R.string.project_primary_action_task)}\n${nextTask.title}"
                binding.projectPrimaryActionView.isClickable = true
                binding.projectPrimaryActionView.isFocusable = true
                binding.projectPrimaryActionView.setOnClickListener {
                    startActivity(
                        Intent(this, TaskDetailActivity::class.java).apply {
                            putExtra("task_id", nextTask.taskId)
                            putExtra("session_id", "")
                        }
                    )
                }
            }
            else -> {
                binding.projectPrimaryActionView.visibility = View.GONE
                binding.projectPrimaryActionView.setOnClickListener(null)
            }
        }
    }

    private fun openSession(sessionId: String, sessionName: String) {
        startActivity(
            Intent(this, SessionDetailActivity::class.java).apply {
                putExtra("session_id", sessionId)
                putExtra("session_name", sessionName)
            }
        )
    }

    private fun renderActiveSessions(sessions: List<ProjectSessionSummary>) {
        binding.activeSessionsContainer.removeAllViews()
        if (sessions.isEmpty()) {
            binding.sessionsSection.visibility = View.GONE
            return
        }
        binding.sessionsSection.visibility = View.VISIBLE

        val paddingHorizontal = (16 * resources.displayMetrics.density).toInt()
        val paddingVertical = (12 * resources.displayMetrics.density).toInt()
        val sessionBottomMargin = (8 * resources.displayMetrics.density).toInt()

        sessions.forEach { session ->
            val sessionView = TextView(this).apply {
                val activeMarker = if (session.isActive) " • live" else ""
                val shortName = session.name.takeIf { it.length <= 42 } ?: "${session.name.take(39)}..."
                val statusLabel = session.status.replace('_', ' ').replaceFirstChar { it.uppercase() }
                text = "$shortName\n#${session.id} • $statusLabel$activeMarker"
                textSize = 13f
                setBackgroundResource(R.drawable.bg_overview_panel)
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (session.isActive) R.color.status_running else R.color.text_secondary
                    )
                )
                setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    openSession(session.id.toString(), session.name)
                }
                setOnLongClickListener {
                    CommandAssist.showSessionActions(
                        this@ProjectDetailActivity,
                        session.id.toString(),
                        session.name
                    )
                    true
                }
            }
            val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = sessionBottomMargin
            }
            sessionView.layoutParams = layoutParams
            binding.activeSessionsContainer.addView(sessionView)
        }
    }
}
