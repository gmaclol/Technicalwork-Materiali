package com.technicalwork.materiali

/**
 * Rappresenta lo stato della UI durante le operazioni sui file Excel.
 */
sealed class UiState {
    object Initial : UiState()
    object Loading : UiState()
    data class Success(val data: List<ExcelRowData>) : UiState()
    data class Error(val message: String, val isInvalidFormat: Boolean = false, val id: Long = System.currentTimeMillis()) : UiState()
}

/**
 * Fotografia dello stato dei dati in un determinato istante (usato per l'Undo).
 */
data class UndoSnapshot(
    val data: List<ExcelRowData>,
    val timestamp: String,
    val epochMillis: Long = System.currentTimeMillis()
)
