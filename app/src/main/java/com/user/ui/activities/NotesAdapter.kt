package com.user.ui.activities

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.user.data.Note
import com.user.databinding.ItemNoteBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotesAdapter(
    private val onTap: (Note) -> Unit,
    private val onLongPress: (Note) -> Unit
) : ListAdapter<Note, NotesAdapter.NoteViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val binding = ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return NoteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NoteViewHolder(private val binding: ItemNoteBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(note: Note) {
            binding.noteTitle.text = note.title.ifBlank {
                binding.root.context.getString(com.user.R.string.note_untitled)
            }
            binding.noteBodyPreview.text = note.body
            binding.noteTags.text = parseTags(note.tags).joinToString(" · ") { "#$it" }
            binding.noteDate.text = DATE_FMT.format(Date(note.createdAt))
            binding.root.setOnClickListener { onTap(note) }
            binding.root.setOnLongClickListener { onLongPress(note); true }
        }
    }

    private fun parseTags(tagsJson: String): List<String> {
        return try {
            val json = org.json.JSONArray(tagsJson)
            (0 until json.length()).map { json.getString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private val DATE_FMT = SimpleDateFormat("MMM d", Locale.getDefault())

        private val DIFF = object : DiffUtil.ItemCallback<Note>() {
            override fun areItemsTheSame(a: Note, b: Note) = a.id == b.id
            override fun areContentsTheSame(a: Note, b: Note) = a == b
        }
    }
}
