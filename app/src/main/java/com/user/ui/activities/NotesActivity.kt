package com.user.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.user.R
import com.user.databinding.ActivityNotesBinding
import com.user.viewmodel.NoteViewModel

class NotesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNotesBinding
    private val viewModel: NoteViewModel by viewModels()
    private lateinit var adapter: NotesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.notes_title)

        adapter = NotesAdapter(
            onTap = { note ->
                startActivity(NoteEditorActivity.editIntent(this, note.id))
            },
            onLongPress = { note ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.note_delete_title)
                    .setMessage(R.string.note_delete_message)
                    .setPositiveButton(R.string.note_delete_title) { _, _ ->
                        viewModel.delete(note) {
                            Toast.makeText(this, R.string.note_deleted, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        )
        binding.notesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.notesRecyclerView.adapter = adapter

        viewModel.notes.observe(this) { notes ->
            adapter.submitList(notes)
            binding.notesEmptyView.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        }

        binding.notesFab.setOnClickListener {
            startActivity(NoteEditorActivity.createIntent(this))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
