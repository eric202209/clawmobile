package com.user.ui.tasks

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.user.data.MobileCheckpoint
import com.user.databinding.ItemCheckpointBinding
import com.user.ui.TimeFormatUtils

class CheckpointAdapter(
    private val onLoad: (MobileCheckpoint) -> Unit,
    private val onDelete: (MobileCheckpoint, Int) -> Unit
) : ListAdapter<MobileCheckpoint, CheckpointAdapter.ViewHolder>(DIFF) {

    private var selectionMode = false
    private val selected = mutableSetOf<String>()

    fun getSelectedNames(): List<String> = selected.toList()

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!enabled) selected.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemCheckpointBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemCheckpointBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(checkpoint: MobileCheckpoint) {
            binding.checkpointName.text = checkpoint.name
            val steps = if (checkpoint.completedSteps > 0) {
                binding.root.context.getString(
                    com.user.R.string.checkpoints_steps, checkpoint.completedSteps
                )
            } else ""
            val time = checkpoint.createdAt
                ?.let { TimeFormatUtils.formatApiTimestamp(it) ?: it }
                ?: ""
            val type = checkpoint.checkpointType.orEmpty()
            val description = checkpoint.description.orEmpty()
            val mode = if (checkpoint.resumable) "" else "Inspect only"
            binding.checkpointMeta.text = listOf(time, type, steps, description, mode)
                .filter { it.isNotBlank() }
                .joinToString(" · ")

            binding.loadButton.visibility = if (selectionMode || !checkpoint.resumable) View.GONE else View.VISIBLE
            binding.selectCheckbox.visibility = if (selectionMode) View.VISIBLE else View.GONE

            if (selectionMode) {
                binding.selectCheckbox.isChecked = checkpoint.name in selected
                binding.selectCheckbox.setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(checkpoint.name) else selected.remove(checkpoint.name)
                }
            } else {
                binding.loadButton.setOnClickListener { onLoad(checkpoint) }
                binding.root.setOnLongClickListener {
                    if (!checkpoint.resumable) return@setOnLongClickListener true
                    setSelectionMode(true)
                    selected.add(checkpoint.name)
                    notifyDataSetChanged()
                    true
                }
            }
        }
    }

    fun attachSwipeToDismiss(recyclerView: RecyclerView) {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.adapterPosition
                if (pos != RecyclerView.NO_ID.toInt()) {
                    val item = getItem(pos)
                    onDelete(item, pos)
                }
            }
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val pos = vh.adapterPosition
                if (pos == RecyclerView.NO_POSITION || !getItem(pos).resumable) return 0
                return super.getSwipeDirs(rv, vh)
            }
            override fun isItemViewSwipeEnabled() = !selectionMode
        }).attachToRecyclerView(recyclerView)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MobileCheckpoint>() {
            override fun areItemsTheSame(a: MobileCheckpoint, b: MobileCheckpoint) = a.name == b.name
            override fun areContentsTheSame(a: MobileCheckpoint, b: MobileCheckpoint) = a == b
        }
    }
}
