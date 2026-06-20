package com.technicalwork.materiali

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class ListUpdater {

    private val client = NetworkClient.okHttpClient
    private val baseUrl = "https://raw.githubusercontent.com/gmaclol/Technicalwork-Materiali/master/lists/"

    companion object {
        private const val TAG = "TW_ListUpdater"
    }

    /**
     * Sincronizza le liste scaricandole da GitHub e salvandole in filesDir/lists/.
     * Fallisce silenziosamente in caso di errori o 404.
     */
    suspend fun syncLists(context: Context, companies: List<String>, pfsAreas: List<String>) = withContext(Dispatchers.IO) {
        try {
            val listsDir = File(context.filesDir, "lists")
            if (!listsDir.exists()) {
                listsDir.mkdirs()
            }

            // Pulizia file locali obsoleti (es. se una company è stata rimossa su GitHub)
            val allowedFiles = mutableSetOf("lista.txt")
            allowedFiles.addAll(companies.map { "$it.txt" })
            allowedFiles.addAll(pfsAreas.map { "$it.txt" })
            
            try {
                listsDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".txt")) {
                        if (!allowedFiles.contains(file.name)) {
                            file.delete()
                            Log.i(TAG, "Cancellato file di lista obsoleto: ${file.name}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore durante la pulizia dei file obsoleti: ${e.message}", e)
            }

            // Costruisce la lista dei file da scaricare dinamicamente
            val filesToDownload = mutableListOf("lista.txt")
            filesToDownload.addAll(companies.map { "$it.txt" })
            filesToDownload.addAll(pfsAreas.map { "$it.txt" })

            filesToDownload.forEach { fileName ->
                val localFile = File(listsDir, fileName)
                try {
                    val url = "$baseUrl$fileName?t=${System.currentTimeMillis()}"
                    val request = Request.Builder().url(url).build()
                    
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val content = response.body?.string()
                            if (!content.isNullOrBlank()) {
                                localFile.writeText(content)
                            } else {
                                if (localFile.exists()) {
                                    localFile.delete()
                                    Log.i(TAG, "Cancellato file locale per contenuto vuoto: $fileName")
                                }
                            }
                        } else {
                            // Se la richiesta non ha successo (es. 404 Not Found), cancelliamo il file locale se esiste
                            if (localFile.exists()) {
                                localFile.delete()
                                Log.i(TAG, "Cancellato file locale $fileName in seguito a risposta HTTP ${response.code}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    // In caso di errore di connessione/download, preferiamo non cancellare il file esistente per non perdere l'offline,
                    // a meno che non sappiamo per certo che non esiste più (es. 404 gestito sopra).
                    Log.w(TAG, "Impossibile scaricare $fileName: ${e.message}")
                }
            }
        } catch (e: Exception) {
            // Ignora errori generali
        }
    }
}
