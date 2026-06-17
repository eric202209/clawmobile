package com.user.ui.tasks

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.user.data.HumanGuidanceConflict
import com.user.databinding.ItemGuidanceConflictBinding

class GuidanceConflictAdapter(
    private val onResolve: (HumanGuidanceConflict) -> Unit,
    private val onIgnore: (HumanGuidanceConflict) -> Unit,
) : ListAdapter<HumanGuidanceConflict, GuidanceConflictAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val binding: ItemGuidanceConflictBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(conflict: HumanGuidanceConflict) {
            binding.conflictGuidanceMessage.text = "“${conflict.guidanceMessage}”"
            binding.conflictTaskTitle.text = "Task: ${conflict.taskTitle}"
            binding.conflictExcerpt.text = conflict.conflictExcerpt.ifBlank { "(no excerpt)" }
            binding.conflictPatterns.text = if (conflict.conflictPatterns.isNotEmpty()) {
                "Patterns: ${conflict.conflictPatterns.joinToString(", ")}"
            } else {
                ""
            }
            binding.resolveConflictButton.setOnClickListener { onResolve(conflict) }
            binding.ignoreConflictButton.setOnClickListener { onIgnore(conflict) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGuidanceConflictBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HumanGuidanceConflict>() {
            override fun areItemsTheSame(a: HumanGuidanceConflict, b: HumanGuidanceConflict) = a.id == b.id
            override fun areContentsTheSame(a: HumanGuidanceConflict, b: HumanGuidanceConflict) = a == b
        }
    }
}
