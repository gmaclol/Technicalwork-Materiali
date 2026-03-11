package com.technicalwork.materiali

import android.content.Context
import java.io.File
import java.io.FileOutputStream

class AssetsHelper {

    /**
     * Legge una lista di materiali.
     * Ordine di priorità:
     * 1) filesDir/lists/${company}.txt
     * 2) filesDir/lists/lista.txt
     * 3) assets/${company}.txt (se esiste) o assets/lista.txt
     */
    fun loadMasterList(context: Context, company: String? = null): List<String> {
        val listsDir = File(context.filesDir, "lists")

        // 1. Tenta da filesDir con il nome della company
        if (company != null) {
            val companyFile = File(listsDir, "${company}.txt")
            if (companyFile.exists()) {
                return readLinesFromFile(companyFile)
            }
        }

        // 2. Tenta da filesDir con il nome generico "lista.txt"
        val genericFile = File(listsDir, "lista.txt")
        if (genericFile.exists()) {
            return readLinesFromFile(genericFile)
        }

        // 3. Fallback finale agli assets (logica originale)
        val list = mutableListOf<String>()
        val assetFileName = if (company != null) {
            try {
                context.assets.open("${company}.txt").close()
                "${company}.txt"
            } catch (_: Exception) {
                "lista.txt"
            }
        } else {
            "lista.txt"
        }

        try {
            context.assets.open(assetFileName).bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (line.isNotBlank()) list.add(line.trim())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return list
    }

    private fun readLinesFromFile(file: File): List<String> {
        val list = mutableListOf<String>()
        try {
            file.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (line.isNotBlank()) list.add(line.trim())
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
