package com.technicalwork.materiali

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class HistoryRepository(private val context: Context) {
    private val gson = Gson()

    private fun getHistoryFile(company: String): File {
        return File(context.filesDir, "history_${company}.json")
    }

    fun saveHistory(company: String, history: List<UndoSnapshot>) {
        try {
            getHistoryFile(company).writeText(gson.toJson(history))
        } catch (_: Exception) {}
    }

    fun loadHistory(company: String): List<UndoSnapshot> {
        val file = getHistoryFile(company)
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<UndoSnapshot>>() {}.type
            gson.fromJson(file.readText(), type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun cleanOldSnapshots(company: String) {
        val history = loadHistory(company)
        if (history.isEmpty()) return
        val tenDaysAgo = System.currentTimeMillis() - (10L * 24 * 60 * 60 * 1000)
        val filtered = history.filter { it.epochMillis >= tenDaysAgo }
        if (filtered.size != history.size) saveHistory(company, filtered)
    }
}
