package com.technicalwork.materiali

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

class PfsSyncQueue {

    companion object {
        private const val TAG = "TW_PfsSyncQueue"
    }

    private val gson = Gson()
    private val PREFS_NAME = "pfs_sync_queue"

    data class QueuedPfsItem(
        val type: String, // "LOG" o "SIGNAL"
        val data: Map<String, Any>,
        val id: String
    )

    fun save(context: Context, type: String, data: Map<String, Any>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = "pfs_${UUID.randomUUID()}"
        val item = QueuedPfsItem(type, data, id)
        val json = gson.toJson(item)
        prefs.edit().putString(id, json).apply()
    }

    suspend fun flush(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val allEntries = prefs.all
        if (allEntries.isEmpty()) return@withContext

        // Garantisce l'autenticazione anonima Firebase prima di inviare a Firestore
        AuthManager.ensureAuthenticated()

        val db = Firebase.firestore
        for ((key, json) in allEntries) {
            if (json is String) {
                try {
                    val item = gson.fromJson(json, QueuedPfsItem::class.java)
                    val collection = if (item.type == "LOG") "pfs_logs" else "pfs_segnalati"
                    
                    // Invio a Firestore
                    db.collection(collection).add(item.data).await()
                    
                    // Se successo, rimuovi dalla coda
                    prefs.edit().remove(key).apply()
                } catch (e: Exception) {
                    Log.w(TAG, "Flush PFS fallito (riprovo al prossimo giro): ${e.message}", e)
                }
            }
        }
    }

    fun hasPending(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all.isNotEmpty()
    }
}
