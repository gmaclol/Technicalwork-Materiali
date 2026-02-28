package com.technicalwork.materiali

import android.content.Context
import java.io.File
import java.io.FileOutputStream

class AssetsHelper {

    /**
     * Legge il file lista.txt dagli assets e restituisce una lista di stringhe.
     * Ogni riga del file corrisponde a un elemento della lista. Le righe vuote vengono ignorate.
     */
    fun loadMasterList(context: Context): List<String> {
        val list = mutableListOf<String>()
        try {
            context.assets.open("lista.txt").bufferedReader().use { reader ->
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
