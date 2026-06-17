package com.user.ui.tasks

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.user.data.HumanGuidanceEntry
import com.user.databinding.ItemGuidanceEntryBinding

class GuidanceEntryAdapter(
    private val onEdit: (HumanGuidanceEntry) -> Unit,
    private val onArchive: (HumanGuidanceEntry) -> Unit,
) : ListAdapter<HumanGuidanceEntry, GuidanceEntryAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val binding: ItemGuidanceEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: HumanGuidanceEntry) {
            binding.guidanceMessage.text = entry.message
            binding.guidancePriority.text = "p${entry.priority}"
            binding.guidanceScope.text = entry.scope
            binding.guidanceStatus.text = entry.status
            binding.guidanceStatus.setTextColor(
                if (entry.status == "active") 0xFF3FB950.toInt() else 0xFF8B949E.toInt()
            )
            binding.guidanceBackendChip.text = entry.backendTargets.joinToString(",")
            binding.guidancePurposeChip.text = entry.purposeTargets.joinToString(",")
            binding.editGuidanceButton.setOnClickListener { onEdit(entry) }
            binding.archiveGuidanceButton.setOnClickListener { onArchive(entry) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGuidanceEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HumanGuidanceEntry>() {
            override fun areItemsTheSame(a: HumanGuidanceEntry, b: HumanGuidanceEntry) = a.id == b.id
            override fun areContentsTheSame(a: HumanGuidanceEntry, b: HumanGuidanceEntry) = a == b
        }
    }
}
