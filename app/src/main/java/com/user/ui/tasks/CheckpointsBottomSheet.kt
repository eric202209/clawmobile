package com.user.ui.tasks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.user.ClawMobileApplication
import com.user.R
import com.user.data.MobileCheckpoint
import com.user.databinding.BottomSheetCheckpointsBinding
import com.user.service.OrchestratorApiClient
import kotlinx.coroutines.launch

class CheckpointsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCheckpointsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: CheckpointAdapter
    private var orchestratorClient: OrchestratorApiClient? = null
    private var sessionId: String = ""
    private var currentCheckpoints: MutableList<MobileCheckpoint> = mutableListOf()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetCheckpointsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sessionId = arguments?.getString(ARG_SESSION_ID) ?: ""
        val app = requireActivity().application as ClawMobileApplication
        if (app.prefsManager.isOrchestratorConfigured()) {
            orchestratorClient = OrchestratorApiClient(app.prefsManager, app.prefsManager.gatewayToken)
        }

        adapter = CheckpointAdapter(
            onLoad = { checkpoint -> confirmLoad(checkpoint) },
            onDelete = { checkpoint, pos -> deleteCheckpoint(checkpoint, pos) }
        )

        binding.checkpointList.layoutManager = LinearLayoutManager(requireContext())
        binding.checkpointList.adapter = adapter
        adapter.attachSwipeToDismiss(binding.checkpointList)

        binding.batchDeleteButton.setOnClickListener { batchDelete() }

        loadCheckpoints()
    }

    private fun loadCheckpoints() {
        val client = orchestratorClient ?: run {
            binding.emptyText.text = "Orchestrator is not configured"
            binding.emptyText.visibility = View.VISIBLE
            binding.checkpointList.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            client.getSessionCheckpoints(sessionId).onSuccess { response ->
                currentCheckpoints = response.checkpoints.toMutableList()
                adapter.submitList(currentCheckpoints.toList())
                binding.emptyText.text = getString(R.string.checkpoints_empty)
                binding.emptyText.visibility = if (currentCheckpoints.isEmpty()) View.VISIBLE else View.GONE
                binding.checkpointList.visibility = if (currentCheckpoints.isEmpty()) View.GONE else View.VISIBLE
            }.onFailure { error ->
                binding.emptyText.text = error.message ?: getString(R.string.checkpoints_empty)
                binding.emptyText.visibility = View.VISIBLE
                binding.checkpointList.visibility = View.GONE
            }
        }
    }

    private fun confirmLoad(checkpoint: MobileCheckpoint) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.checkpoints_load_confirm_title)
            .setMessage(getString(R.string.checkpoints_load_confirm_message, checkpoint.name))
            .setPositiveButton(R.string.checkpoints_load) { _, _ ->
                doLoadCheckpoint(checkpoint)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doLoadCheckpoint(checkpoint: MobileCheckpoint) {
        val client = orchestratorClient ?: return
        lifecycleScope.launch {
            client.loadCheckpoint(sessionId, checkpoint.name).onSuccess {
                Snackbar.make(binding.root, R.string.checkpoints_load_success, Snackbar.LENGTH_SHORT).show()
                dismiss()
            }.onFailure { error ->
                Snackbar.make(
                    binding.root,
                    error.message ?: getString(R.string.checkpoints_load_failed),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun deleteCheckpoint(checkpoint: MobileCheckpoint, position: Int) {
        val client = orchestratorClient ?: return
        val removedList = currentCheckpoints.toMutableList().also { it.removeAt(position) }
        currentCheckpoints.removeAt(position)
        adapter.submitList(removedList.toList())

        Snackbar.make(binding.root, R.string.checkpoints_delete_success, Snackbar.LENGTH_LONG)
            .setAction(R.string.checkpoints_undo) {
                currentCheckpoints.add(position, checkpoint)
                adapter.submitList(currentCheckpoints.toList())
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(snackbar: Snackbar, event: Int) {
                    if (event != DISMISS_EVENT_ACTION) {
                        lifecycleScope.launch {
                            client.deleteCheckpoint(sessionId, checkpoint.name).onFailure {
                                currentCheckpoints.add(position.coerceAtMost(currentCheckpoints.size), checkpoint)
                                adapter.submitList(currentCheckpoints.toList())
                            }
                        }
                    }
                }
            }).show()
    }

    private fun batchDelete() {
        val names = adapter.getSelectedNames()
        if (names.isEmpty()) return
        val client = orchestratorClient ?: return
        lifecycleScope.launch {
            var successCount = 0
            names.forEach { name ->
                client.deleteCheckpoint(sessionId, name).onSuccess { successCount++ }
            }
            adapter.setSelectionMode(false)
            binding.batchDeleteButton.visibility = View.GONE
            loadCheckpoints()
            Snackbar.make(binding.root, "$successCount checkpoint(s) deleted", Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_SESSION_ID = "session_id"

        fun newInstance(sessionId: String) = CheckpointsBottomSheet().apply {
            arguments = Bundle().apply { putString(ARG_SESSION_ID, sessionId) }
        }
    }
}
