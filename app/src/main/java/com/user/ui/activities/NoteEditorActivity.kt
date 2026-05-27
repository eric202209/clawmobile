package com.user.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.user.ClawMobileApplication
import com.user.R
import com.user.data.Note
import com.user.databinding.ActivityNoteEditorBinding
import com.user.viewmodel.NoteViewModel
import kotlinx.coroutines.launch
import org.json.JSONArray

class NoteEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNoteEditorBinding
    private val viewModel: NoteViewModel by viewModels()
    private var existingNote: Note? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        if (noteId != -1L) {
            title = getString(R.string.note_edit)
            lifecycleScope.launch {
                val note = (application as ClawMobileApplication).noteDao.getById(noteId)
                note?.let { populate(it) }
            }
        } else {
            title = getString(R.string.note_new)
        }

        binding.noteSaveButton.setOnClickListener { save() }
    }

    private fun populate(note: Note) {
        existingNote = note
        binding.noteTitleInput.setText(note.title)
        binding.noteBodyInput.setText(note.body)
        binding.noteTagsInput.setText(parseTags(note.tags).joinToString(", "))
    }

    private fun save() {
        val title = binding.noteTitleInput.text?.toString()?.trim() ?: ""
        val body = binding.noteBodyInput.text?.toString()?.trim() ?: ""
        val tagsRaw = binding.noteTagsInput.text?.toString()?.trim() ?: ""
        val tagsJson = tagsRaw
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .let { list -> JSONArray(list).toString() }

        val note = existingNote?.copy(title = title, body = body, tags = tagsJson)
            ?: Note(title = title, body = body, tags = tagsJson)
        binding.noteSaveButton.isEnabled = false
        viewModel.save(note) {
            Toast.makeText(this, R.string.note_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun parseTags(tagsJson: String): List<String> {
        return try {
            val arr = JSONArray(tagsJson)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val EXTRA_NOTE_ID = "note_id"

        fun createIntent(context: Context): Intent =
            Intent(context, NoteEditorActivity::class.java)

        fun editIntent(context: Context, noteId: Long): Intent =
            Intent(context, NoteEditorActivity::class.java)
                .putExtra(EXTRA_NOTE_ID, noteId)
    }
}
