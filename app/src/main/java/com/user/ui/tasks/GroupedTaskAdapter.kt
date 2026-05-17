package com.user.ui.tasks

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.user.R
import com.user.data.OrchestTask
import com.user.ui.components.StatusBadgeView

class GroupedTaskAdapter(
    private val onHeaderClick: (String) -> Unit,
    private val onTaskClick: (OrchestTask) -> Unit,
    private val onTaskLongPress: ((OrchestTask) -> Unit)? = null,
) : ListAdapter<GroupedTaskAdapter.ListItem, RecyclerView.ViewHolder>(DiffCallback()) {

    sealed class ListItem {
        data class Header(
            val projectId: String,
            val projectName: String,
            val count: Int,
            val isExpanded: Boolean,
        ) : ListItem()

        data class Task(val task: OrchestTask) : ListItem()
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TASK = 1
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is ListItem.Header -> TYPE_HEADER
        is ListItem.Task -> TYPE_TASK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inflater.inflate(R.layout.item_project_header, parent, false))
        } else {
            TaskVH(inflater.inflate(R.layout.item_task, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ListItem.Header -> (holder as HeaderVH).bind(item)
            is ListItem.Task -> (holder as TaskVH).bind(item.task)
        }
    }

    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val name: TextView = v.findViewById(R.id.projectHeaderName)
        private val count: TextView = v.findViewById(R.id.projectHeaderCount)
        private val chevron: View = v.findViewById(R.id.projectHeaderChevron)
        fun bind(h: ListItem.Header) {
            name.text = h.projectName
            count.text = "${h.count} task${if (h.count == 1) "" else "s"}"
            chevron.rotation = if (h.isExpanded) 180f else 0f
            itemView.setOnClickListener { onHeaderClick(h.projectId) }
        }
    }

    inner class TaskVH(v: View) : RecyclerView.ViewHolder(v) {
        private val titleView: TextView = v.findViewById(R.id.taskTitle)
        private val descView: TextView = v.findViewById(R.id.taskDescription)
        private val badge: StatusBadgeView = v.findViewById(R.id.taskStatusBadge)
        private val priorityView: TextView = v.findViewById(R.id.taskPriority)
        private val timeView: TextView = v.findViewById(R.id.taskTime)
        private val metaView: TextView = v.findViewById(R.id.taskMeta)
        private val strip: View = v.findViewById(R.id.taskStatusStrip)

        fun bind(task: OrchestTask) {
            titleView.text = task.title
            descView.text = task.description.orEmpty()
            descView.visibility = if (task.description.isNullOrBlank()) View.GONE else View.VISIBLE

            val status = resolveStatus(task)
            badge.setStatus(status)
            strip.setBackgroundColor(
                ContextCompat.getColor(itemView.context, stripColor(status))
            )

            if (task.priority > 0) {
                priorityView.text = "P${task.priority}"
                priorityView.visibility = View.VISIBLE
            } else {
                priorityView.visibility = View.GONE
            }

            timeView.text = task.createdAt.take(10)
            timeView.visibility = View.VISIBLE

            val seq = if (task.sequenceIndex != null && task.sequenceTotal != null)
                "Step ${task.sequenceIndex}/${task.sequenceTotal}" else null
            val session = task.sessionId?.let { "#$it${if (task.hasActiveSession) " • live" else ""}" }
            val metaStr = listOfNotNull(seq, session).joinToString(" • ")
            metaView.text = metaStr
            metaView.visibility = if (metaStr.isBlank()) View.GONE else View.VISIBLE

            itemView.setOnClickListener { onTaskClick(task) }
            itemView.setOnLongClickListener { onTaskLongPress?.invoke(task); true }
        }

        private fun resolveStatus(task: OrchestTask) = when {
            task.status == "unknown" && task.hasActiveSession -> "running"
            task.status == "unknown" -> task.sessionStatus?.lowercase() ?: "pending"
            else -> task.status.lowercase()
        }

        private fun stripColor(status: String) = when (status) {
            "running", "in_progress", "executing", "approved" -> R.color.status_running
            "completed", "done", "success" -> R.color.status_completed
            "failed", "timeout" -> R.color.status_failed
            "rejected" -> R.color.status_failed
            else -> R.color.status_pending
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ListItem>() {
        override fun areItemsTheSame(a: ListItem, b: ListItem) = when {
            a is ListItem.Header && b is ListItem.Header -> a.projectId == b.projectId
            a is ListItem.Task && b is ListItem.Task -> a.task.taskId == b.task.taskId
            else -> false
        }
        override fun areContentsTheSame(a: ListItem, b: ListItem) = a == b
    }
}
