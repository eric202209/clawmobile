package com.user.ui.tasks

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.user.ClawMobileApplication
import com.user.R
import com.user.data.HumanGuidanceConflict
import com.user.data.HumanGuidanceEntry
import com.user.databinding.ActivityHumanGuidanceBinding
import com.user.service.OrchestratorApiClient
import com.user.viewmodel.HumanGuidanceViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HumanGuidanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHumanGuidanceBinding
    private var client: OrchestratorApiClient? = null
    private var projectId: Int = 0
    private var projectName: String = ""
    private var isAdvancedMode: Boolean = false
    private var currentStatus: String = "active"

    private lateinit var guidanceAdapter: GuidanceEntryAdapter
    private lateinit var conflictAdapter: GuidanceConflictAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHumanGuidanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectId = intent.getIntExtra("project_id", 0)
        projectName = intent.getStringExtra("project_name") ?: "Project"

        val app = application as ClawMobileApplication
        if (app.prefsManager.isOrchestratorConfigured()) {
            client = OrchestratorApiClient(app.prefsManager, app.prefsManager.gatewayToken)
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Guidance — $projectName"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupAdapters()
        setupListeners()
        loadAll()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupAdapters() {
        guidanceAdapter = GuidanceEntryAdapter(
            onEdit = { entry -> showEditDialog(entry) },
            onArchive = { entry -> confirmArchive(entry) },
        )
        binding.guidanceRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@HumanGuidanceActivity)
            adapter = guidanceAdapter
        }

        conflictAdapter = GuidanceConflictAdapter(
            onResolve = { conflict -> resolveConflict(conflict, "resolved") },
            onIgnore = { conflict -> resolveConflict(conflict, "ignored") },
        )
        binding.conflictsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@HumanGuidanceActivity)
            adapter = conflictAdapter
        }
    }

    private fun setupListeners() {
        binding.conflictsAccordionHeader.setOnClickListener {
            val isVisible = binding.conflictsContent.visibility == View.VISIBLE
            binding.conflictsContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.conflictsChevron.rotation = if (isVisible) 0f else 180f
        }

        binding.advancedModeSwitch.setOnCheckedChangeListener { _, checked ->
            isAdvancedMode = checked
            binding.previewCard.visibility = if (checked) View.VISIBLE else View.GONE
        }

        binding.addGuidanceButton.setOnClickListener {
            AddGuidanceBottomSheet(
                isGlobalAdvancedMode = isAdvancedMode,
                onSave = { payload -> createGuidance(payload) },
            ).show(supportFragmentManager, "add_guidance")
        }

        binding.enableGuidanceButton.setOnClickListener { enableGuidance() }

        binding.loadPreviewButton.setOnClickListener { loadPreview() }

        binding.filterChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentStatus = when (checkedIds.firstOrNull()) {
                R.id.chipDisabled -> "disabled"
                R.id.chipAll -> "all"
                else -> "active"
            }
            loadGuidance()
        }
    }

    private fun loadAll() {
        val c = client ?: run {
            Snackbar.make(binding.root, "Orchestrator not configured", Snackbar.LENGTH_LONG).show()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            c.getGuidanceReadiness(projectId).onSuccess { readiness ->
                val isReady = readiness.ready
                binding.statusBadge.text = if (isReady) "Active" else "Inactive"
                binding.statusBadge.setTextColor(
                    if (isReady) getColor(R.color.status_approved) else getColor(R.color.text_secondary)
                )
                binding.activeCountText.text = readiness.guidanceStatistics.activeGuidance.toString()

                val stats = readiness.purposeStatistics
                binding.planningCountText.text = (stats.planning + stats.execution + stats.repair).toString()

                val reasons = readiness.blockingReasons
                if (reasons.isNotEmpty()) {
                    binding.warningText.text = reasons.joinToString("\n") { it.replace('_', ' ') }
                    binding.warningText.visibility = View.VISIBLE
                } else {
                    binding.warningText.visibility = View.GONE
                }

                binding.enableGuidanceButton.visibility =
                    if (!isReady) View.VISIBLE else View.GONE

            }.onFailure {
                Snackbar.make(binding.root, "Failed to load readiness: ${it.message}", Snackbar.LENGTH_LONG).show()
            }
        }

        loadGuidance()
        loadConflicts()
    }

    private fun loadGuidance() {
        val c = client ?: return
        CoroutineScope(Dispatchers.Main).launch {
            c.listGuidance(projectId, currentStatus).onSuccess { result ->
                guidanceAdapter.submitList(result.items)
                binding.guidanceEmptyText.visibility =
                    if (result.items.isEmpty()) View.VISIBLE else View.GONE
            }.onFailure {
                binding.guidanceEmptyText.text = it.message ?: "Failed to load guidance"
                binding.guidanceEmptyText.visibility = View.VISIBLE
            }
        }
    }

    private fun loadConflicts() {
        val c = client ?: return
        CoroutineScope(Dispatchers.Main).launch {
            c.listGuidanceConflicts(projectId).onSuccess { result ->
                conflictAdapter.submitList(result.items)
                val count = result.items.size
                if (count > 0) {
                    binding.conflictCountText.text = count.toString()
                    binding.conflictBadge.text = "$count"
                    binding.conflictBadge.visibility = View.VISIBLE
                } else {
                    binding.conflictCountText.text = "0"
                    binding.conflictBadge.visibility = View.GONE
                }
                binding.conflictsEmptyText.visibility =
                    if (result.items.isEmpty()) View.VISIBLE else View.GONE
            }.onFailure {
                binding.conflictCountText.text = "—"
            }
        }
    }

    private fun createGuidance(payload: Map<String, Any>) {
        val c = client ?: return
        CoroutineScope(Dispatchers.Main).launch {
            c.createGuidance(projectId, payload).onSuccess {
                Snackbar.make(binding.root, "Guidance added", Snackbar.LENGTH_SHORT).show()
                loadAll()
            }.onFailure {
                Snackbar.make(binding.root, "Failed to add: ${it.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmArchive(entry: HumanGuidanceEntry) {
        AlertDialog.Builder(this)
            .setTitle("Archive guidance?")
            .setMessage("This will archive the entry. It can be re-activated later.")
            .setPositiveButton("Archive") { _, _ -> archiveGuidance(entry) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun archiveGuidance(entry: HumanGuidanceEntry) {
        val c = client ?: return
        CoroutineScope(Dispatchers.Main).launch {
            c.archiveGuidance(entry.id).onSuccess {
                Snackbar.make(binding.root, "Archived", Snackbar.LENGTH_SHORT).show()
                loadGuidance()
            }.onFailure {
                Snackbar.make(binding.root, "Archive failed: ${it.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showEditDialog(entry: HumanGuidanceEntry) {
        val input = android.widget.EditText(this).apply {
            setText(entry.message)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        AlertDialog.Builder(this)
            .setTitle("Edit guidance")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newMessage = input.text.toString().trim()
                if (newMessage.isNotBlank()) {
                    val c = client ?: return@setPositiveButton
                    CoroutineScope(Dispatchers.Main).launch {
                        c.patchGuidance(entry.id, mapOf("message" to newMessage)).onSuccess {
                            Snackbar.make(binding.root, "Updated", Snackbar.LENGTH_SHORT).show()
                            loadGuidance()
                        }.onFailure {
                            Snackbar.make(binding.root, "Update failed: ${it.message}", Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resolveConflict(conflict: HumanGuidanceConflict, action: String) {
        val c = client ?: return
        val conflictId = conflict.id ?: return
        val payload = if (action == "resolved") {
            HumanGuidanceViewModel.buildConflictResolvePayload()
        } else {
            HumanGuidanceViewModel.buildConflictIgnorePayload()
        }
        CoroutineScope(Dispatchers.Main).launch {
            c.patchGuidanceConflict(projectId, conflictId, payload).onSuccess {
                val current = conflictAdapter.currentList.toMutableList()
                current.remove(conflict)
                conflictAdapter.submitList(current)
                if (current.isEmpty()) {
                    binding.conflictBadge.visibility = View.GONE
                    binding.conflictCountText.text = "0"
                    binding.conflictsEmptyText.visibility = View.VISIBLE
                } else {
                    binding.conflictBadge.text = "${current.size}"
                    binding.conflictCountText.text = "${current.size}"
                }
            }.onFailure {
                Snackbar.make(binding.root, "Failed: ${it.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun enableGuidance() {
        val c = client ?: return
        val payload = HumanGuidanceViewModel.buildEnableAllPayload()
        CoroutineScope(Dispatchers.Main).launch {
            c.patchGuidanceActivation(projectId, payload).onSuccess {
                Snackbar.make(binding.root, "Human guidance enabled", Snackbar.LENGTH_SHORT).show()
                binding.enableGuidanceButton.visibility = View.GONE
                loadAll()
            }.onFailure {
                Snackbar.make(binding.root, "Enable failed: ${it.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun loadPreview() {
        val c = client ?: return
        CoroutineScope(Dispatchers.Main).launch {
            c.getRenderedGuidance(projectId).onSuccess { rendered ->
                binding.previewMetaText.text = "${rendered.selectedCount} selected • ${rendered.renderedChars}/${rendered.maxChars} chars" +
                    if (rendered.trimmed) " • trimmed (${rendered.trimmedCount})" else ""
                binding.previewMetaText.visibility = View.VISIBLE
                if (rendered.block.isNotBlank()) {
                    binding.previewBlockText.text = rendered.block
                    binding.previewBlockText.visibility = View.VISIBLE
                } else {
                    binding.previewBlockText.visibility = View.GONE
                }
            }.onFailure {
                Snackbar.make(binding.root, "Preview failed: ${it.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }
}
