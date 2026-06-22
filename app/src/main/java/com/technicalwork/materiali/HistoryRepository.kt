package com.technicalwork.materiali

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class HistoryRepository(private val context: Context) {

    companion object {
        private const val TAG = "TW_HistoryRepo"
    }

    private val gson = Gson()
    private val lock = Any()

    private fun getHistoryFile(company: String): File {
        return File(context.filesDir, "history_${company}.json")
    }

    fun saveHistory(company: String, history: List<UndoSnapshot>) {
        synchronized(lock) {
            val file = getHistoryFile(company)
            try {
                val tempFile = File(context.filesDir, "history_${company}_tmp.json")
                tempFile.writeText(gson.toJson(history))
                tempFile.renameTo(file)
            } catch (e: Exception) {
                Log.e(TAG, "Errore salvataggio history per $company: ${e.message}", e)
            }
        }
    }

    fun loadHistory(company: String): List<UndoSnapshot> {
        synchronized(lock) {
            val file = getHistoryFile(company)
            if (!file.exists()) return emptyList()
            return try {
                val type = object : TypeToken<List<UndoSnapshot>>() {}.type
                gson.fromJson(file.readText(), type) ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Errore caricamento history per $company: ${e.message}", e)
                emptyList()
            }
        }
    }

    fun cleanOldSnapshots(company: String) {
        val history = loadHistory(company)
        if (history.isEmpty()) return
        val tenDaysAgo = System.currentTimeMillis() - (10L * 24 * 60 * 60 * 1000)
        val filtered = history.filter { it.epochMillis >= tenDaysAgo }
        if (filtered.size != history.size) saveHistory(company, filtered)
    }
}
