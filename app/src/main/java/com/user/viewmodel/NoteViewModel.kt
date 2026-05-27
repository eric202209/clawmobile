package com.user.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.user.ClawMobileApplication
import com.user.data.Note
import kotlinx.coroutines.launch

class NoteViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as ClawMobileApplication).noteDao

    val notes: LiveData<List<Note>> = dao.getAll().asLiveData()

    fun save(note: Note, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            dao.insert(note)
            onSaved()
        }
    }

    fun delete(note: Note, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            dao.delete(note)
            onDeleted()
        }
    }
}
