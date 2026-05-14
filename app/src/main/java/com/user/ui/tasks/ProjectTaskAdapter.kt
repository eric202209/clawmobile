package com.user.ui.tasks

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.user.data.OrchestTask
import com.user.databinding.ItemProjectTaskBinding

class ProjectTaskAdapter(
    private val onTaskClick: (OrchestTask) -> Unit,
    private val onTaskLongPress: ((OrchestTask) -> Unit)? = null,
    private val onDragStart: ((RecyclerView.ViewHolder) -> Unit)? = null,
) : ListAdapter<OrchestTask, ProjectTaskAdapter.ProjectTaskViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectTaskViewHolder {
        val binding = ItemProjectTaskBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ProjectTaskViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProjectTaskViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ProjectTaskViewHolder(
        private val binding: ItemProjectTaskBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(task: OrchestTask) {
            binding.taskTitle.text = task.title
            binding.taskDescription.text = task.description?.takeIf { it.isNotBlank() } ?: "No description"
            binding.taskDescription.visibility =
                if (task.description.isNullOrBlank()) View.GONE else View.VISIBLE

            val isDraggable = task.status.lowercase() == "pending"
            binding.dragHandle.visibility = if (isDraggable) View.VISIBLE else View.GONE
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    onDragStart?.invoke(this)
                }
                false
            }

            val effectiveStatus = when {
                task.status == "unknown" && task.hasActiveSession -> "running"
                task.status == "unknown" -> task.sessionStatus?.lowercase() ?: "pending"
                else -> task.status.lowercase()
            }
            binding.taskStatus.text = when (effectiveStatus) {
                "failed" -> "Needs attention"
                "running", "executing", "in_progress" -> "Running"
                "pending" -> "Waiting"
                "done", "completed", "success" -> "Done"
                "approved" -> "Approved"
                "timeout" -> "Timed out"
                "cancelled", "canceled" -> "Cancelled"
                "rejected" -> "Rejected"
                else -> effectiveStatus.replace('_', ' ').replaceFirstChar { it.uppercase() }
            }

            val progression = when {
                task.sequenceIndex != null && task.sequenceTotal != null ->
                    "Step ${task.sequenceIndex} of ${task.sequenceTotal}"
                else -> null
            }
            val latestRun = when {
                task.sessionId != null -> {
                    val activeMarker = if (task.hasActiveSession) " • live" else ""
                    val sessionStatus = task.sessionStatus
                        ?.replace('_', ' ')
                        ?.replaceFirstChar { it.uppercase() }
                        ?: "Linked"
                    "#${task.sessionId} • $sessionStatus$activeMarker"
                }
                else -> "No run yet"
            }

            binding.taskMeta.text = listOfNotNull(
                progression,
                "Latest run: $latestRun"
            ).joinToString("\n")

            binding.root.setOnClickListener { onTaskClick(task) }
            binding.root.setOnLongClickListener {
                onTaskLongPress?.invoke(task)
                true
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<OrchestTask>() {
        override fun areItemsTheSame(oldItem: OrchestTask, newItem: OrchestTask): Boolean {
            return oldItem.taskId == newItem.taskId
        }

        override fun areContentsTheSame(oldItem: OrchestTask, newItem: OrchestTask): Boolean {
            return oldItem == newItem
        }
    }
}
