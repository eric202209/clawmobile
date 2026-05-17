package com.user.ui.tasks

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.user.R
import com.user.data.Task
import com.user.ui.components.StatusBadgeView

/**
 * Adapter for displaying tasks in a RecyclerView
 */
class TaskAdapter(
    private val onApproveClick: (Task) -> Unit,
    private val onRejectClick: (Task) -> Unit,
    private val onStartClick: (Task) -> Unit,
    private val onViewClick: (Task) -> Unit,
    private val onLongPress: ((Task) -> Unit)? = null,
) : ListAdapter<Task, TaskAdapter.TaskViewHolder>(TaskDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TaskViewHolder(itemView: View) : ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.taskTitle)
        private val descriptionText: TextView = itemView.findViewById(R.id.taskDescription)
        private val statusBadge: StatusBadgeView = itemView.findViewById(R.id.taskStatusBadge)
        private val priorityText: TextView = itemView.findViewById(R.id.taskPriority)
        private val timeText: TextView = itemView.findViewById(R.id.taskTime)

        fun bind(task: Task) {
            titleText.text = task.title
            descriptionText.text = task.description.take(100) +
                    if (task.description.length > 100) "..." else ""
            statusBadge.setStatus(task.status.name.lowercase())
            priorityText.text = "Priority: ${task.priority}"
            timeText.text = formatTime(task.createdAt)

            itemView.setOnClickListener { onViewClick(task) }
            itemView.setOnLongClickListener {
                onLongPress?.invoke(task)
                true
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
    }

    abstract class ViewHolder(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)

    class TaskDiffCallback : DiffUtil.ItemCallback<Task>() {
        override fun areItemsTheSame(oldItem: Task, newItem: Task): Boolean {
            return oldItem.taskId == newItem.taskId
        }

        override fun areContentsTheSame(oldItem: Task, newItem: Task): Boolean {
            return oldItem == newItem
        }
    }
}