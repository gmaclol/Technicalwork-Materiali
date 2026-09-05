package com.technicalwork.materiali

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncQueue {
    private val gson = Gson()

    data class QueuedData(
        val company: String,
        val technicianName: String,
        val materials: List<ExcelRowData>,
        val lat: Double?,
        val lng: Double?,
        val deviceId: String
    )

    fun save(context: Context, company: String, technicianName: String, materials: List<ExcelRowData>, lat: Double?, lng: Double?, deviceId: String) {
        val prefs = context.getSharedPreferences("sync_queue", Context.MODE_PRIVATE)
        val data = QueuedData(company, technicianName, materials, lat, lng, deviceId)
        val json = gson.toJson(data)
        prefs.edit().putString("${company}_${technicianName}", json).apply()
    }

    suspend fun flush(context: Context, firebaseRepository: FirebaseRepository) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("sync_queue", Context.MODE_PRIVATE)
        val allEntries = prefs.all
        if (allEntries.isEmpty()) return@withContext

        // Garantisce l'autenticazione anonima Firebase prima di inviare i materiali
        AuthManager.ensureAuthenticated()

        for ((key, json) in allEntries) {
            if (json is String) {
                try {
                    val data = gson.fromJson(json, QueuedData::class.java)
                    // Chiamata al sync (che ora ha il controllo connessione interno)
                    val success = firebaseRepository.syncToFirestore(
                        context,
                        data.company,
                        data.technicianName,
                        data.materials,
                        data.lat,
                        data.lng,
                        isRetry = true,
                        deviceId = data.deviceId
                    )
                    if (success) {
                        prefs.edit().remove(key).apply()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun hasPending(context: Context): Boolean {
        return context.getSharedPreferences("sync_queue", Context.MODE_PRIVATE).all.isNotEmpty()
    }
}
