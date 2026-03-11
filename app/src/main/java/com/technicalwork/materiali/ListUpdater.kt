package com.technicalwork.materiali

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class ListUpdater {

    private val client = OkHttpClient()
    private val baseUrl = "https://raw.githubusercontent.com/gmaclol/Technicalwork-Materiali/master/lists/"
    private val filesToDownload = listOf("lista.txt", "Elecnor.txt", "Sertori.txt", "Sirti.txt")

    /**
     * Sincronizza le liste scaricandole da GitHub e salvandole in filesDir/lists/.
     * Fallisce silenziosamente in caso di errori o 404.
     */
    suspend fun syncLists(context: Context) = withContext(Dispatchers.IO) {
        try {
            val listsDir = File(context.filesDir, "lists")
            if (!listsDir.exists()) {
                listsDir.mkdirs()
            }

            filesToDownload.forEach { fileName ->
                try {
                    val url = "$baseUrl$fileName"
                    val request = Request.Builder().url(url).build()
                    
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val content = response.body?.string()
                            if (!content.isNullOrBlank()) {
                                File(listsDir, fileName).writeText(content)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignora errori per singolo file
                }
            }
        } catch (e: Exception) {
            // Ignora errori generali
        }
    }
}
