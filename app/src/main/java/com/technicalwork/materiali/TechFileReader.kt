package com.technicalwork.materiali

import android.content.Context
import android.net.Uri
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.InputStream

class TechFileReader {

    /**
     * Legge un file Excel tramite Uri e restituisce una lista di coppie (Nome, Quantità).
     * Scorre tutte le righe del primo foglio, leggendo la colonna 0 come nome e la colonna 1 come quantità.
     * Le righe con la colonna 0 vuota vengono ignorate.
     * Restituisce null se si verifica un'eccezione reale durante la lettura.
     */
    fun readMaterials(uri: Uri, context: Context): List<Pair<String, String>>? {
        val materialsList = mutableListOf<Pair<String, String>>()
        val dataFormatter = DataFormatter()

        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.use { input ->
                // Imposto il ratio di sicurezza per file compressi
                ZipSecureFile.setMinInflateRatio(0.001)
                
                val workbook = WorkbookFactory.create(input)
                val sheet = workbook.getSheetAt(0)

                for (row in sheet) {
                    val name = dataFormatter.formatCellValue(row.getCell(0))
                    
                    // Ignora le righe dove il nome è vuoto
                    if (name.isNotBlank()) {
                        val quantity = dataFormatter.formatCellValue(row.getCell(1))
                        materialsList.add(Pair(name, quantity))
                    }
                }
                workbook.close()
            }
            materialsList
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
