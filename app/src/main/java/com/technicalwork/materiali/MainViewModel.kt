package com.technicalwork.materiali

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Stack

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ExcelRepository(application)
    private val historyRepository = HistoryRepository(application)

    private val _uiState = MutableStateFlow<UiState>(UiState.Initial)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges.asStateFlow()

    // Undo Stack
    val undoStack = Stack<UndoSnapshot>()
    var currentCompany: String? = null
    var preRevertSnapshot: List<ExcelRowData>? = null

    fun loadExcelFile(uri: Uri, company: String? = null) {
        currentCompany = company
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            val result = repository.readExcelFile(uri, company)
            if (result.isSuccess) {
                val dataList = result.getOrNull() ?: emptyList()
                
                undoStack.clear()
                val saved = historyRepository.loadHistory(company ?: "default")
                if (saved.isNotEmpty()) undoStack.addAll(saved)

                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                undoStack.push(UndoSnapshot(dataList.map { it.copy() }, timestamp))
                
                _hasUnsavedChanges.value = false
                _uiState.value = UiState.Success(dataList)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Errore sconosciuto"
                val isInvalidFormat = errorMsg == "Formato file non valido"
                _uiState.value = UiState.Error(errorMsg, isInvalidFormat)
            }
        }
    }

    fun saveExcelFile(uri: Uri, currentData: List<ExcelRowData>, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val result = repository.saveExcelFile(uri, currentData)
            if (result.isSuccess) {
                _hasUnsavedChanges.value = false
                onComplete?.invoke(true)
            } else {
                onComplete?.invoke(false)
            }
        }
    }

    fun resetExcelFile(uri: Uri, company: String? = null) {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            val result = repository.createFromTemplate(uri, company)
            if (result.isSuccess) {
                // Ricarica i dati resettati
                loadExcelFile(uri, company)
            } else {
                _uiState.value = UiState.Error("Errore reset", false)
            }
        }
    }

    fun createTemplate(uri: Uri, company: String? = null, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val result = repository.createFromTemplate(uri, company)
            if (result.isSuccess) {
                onComplete?.invoke(true)
                loadExcelFile(uri, company)
            } else {
                onComplete?.invoke(false)
            }
        }
    }

    fun saveStateForUndo(currentData: List<ExcelRowData>) {
        val snapshotData = currentData.map { it.copy() }
        if (undoStack.isNotEmpty() && undoStack.peek().data == snapshotData) return
        
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        undoStack.push(UndoSnapshot(snapshotData, timestamp))
        historyRepository.saveHistory(currentCompany ?: "default", undoStack.toList())
        
        // Limita la dimensione dello stack a 20 elementi (più 1 stato iniziale)
        if (undoStack.size > 21) {
            undoStack.removeAt(0)
        }
        _hasUnsavedChanges.value = true
    }

    fun performUndo(onUndoState: (List<ExcelRowData>) -> Unit): Boolean {
        if (undoStack.size > 1) {
            undoStack.pop() // rimuove lo stato corrente
            val previousState = undoStack.peek()
            val restoredData = previousState.data.map { it.copy() }
            
            // Non sovrascriviamo _uiState.value per non rifare il bind completo della RV se non necessario,
            // ritorniamo i dati ripristinati.
            _hasUnsavedChanges.value = true
            onUndoState(restoredData)
            return true
        }
        return false
    }

    fun markAsUnsaved() {
        if (!_hasUnsavedChanges.value) {
            _hasUnsavedChanges.value = true
        }
    }

    fun clearError() {
        _uiState.value = UiState.Initial
    }
}
