package com.technicalwork.materiali

import android.content.Context
import java.io.File
import java.io.FileOutputStream

class AssetsHelper {

    /**
     * Legge una lista di materiali dagli assets.
     * Se [company] è fornito, cerca prima "${company}.txt".
     * Se non fornito o se il file specifico non esiste, usa "lista.txt" come fallback.
     */
    fun loadMasterList(context: Context, company: String? = null): List<String> {
        val list = mutableListOf<String>()
        
        val fileName = if (company != null) {
            val specificFile = "${company}.txt"
            try {
                // Verifichiamo se il file esiste aprendolo e chiudendolo subito
                context.assets.open(specificFile).close()
                specificFile
            } catch (_: Exception) {
                "lista.txt"
            }
        } else {
            "lista.txt"
        }

        try {
            context.assets.open(fileName).bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (line.isNotBlank()) {
                        list.add(line.trim())
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /**
     * Copia il file Sample.xlsx dagli assets in un file temporaneo nella cache dell'app.
     * Restituisce il riferimento al file creato.
     */
    fun copyTemplate(context: Context): File {
        val tempFile = File(context.cacheDir, "Sample.xlsx")
        try {
            context.assets.open("Sample.xlsx").use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return tempFile
    }
}
