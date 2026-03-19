package com.technicalwork.materiali

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConsumoRepository(private val context: Context) {

    private val dataFormatter = DataFormatter()

    /**
     * Legge il file di consumo dall'URI fornito.
     * Prende label dalla colonna A e valore dalla colonna B dalla riga 8 in poi.
     * Pulisce i valori "N°" restituendoli come stringa vuota.
     */
    suspend fun readConsumoFile(uri: Uri): Result<List<ExcelRowData>> = withContext(Dispatchers.IO) {
        try {
            val dataList = mutableListOf<ExcelRowData>()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipSecureFile.setMinInflateRatio(0.001)
                val workbook = WorkbookFactory.create(inputStream)
                val sheet = workbook.getSheetAt(0)

                // Dati dalla riga 8 (indice 7) in poi
                for (i in 7..sheet.lastRowNum) {
                    val row = sheet.getRow(i) ?: continue
                    val label = dataFormatter.formatCellValue(row.getCell(0)).trim()
                    
                    if (label.isNotEmpty()) {
                        val value = dataFormatter.formatCellValue(row.getCell(1)).trim()
                        val cleanValue = if (value == "N°") "" else value
                        dataList.add(ExcelRowData(label, cleanValue))
                    }
                }
                workbook.close()
                Result.success(dataList)
            } ?: Result.failure(Exception("Impossibile aprire il file"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Salva i dati di consumo nel file esistente.
     * Aggiorna data odierna in A6, tecnico in C6 e i valori in colonna B dalla riga 8.
     * Se il valore è vuoto scrive "N°". Preserva gli stili esistenti.
     */
    suspend fun saveConsumoFile(uri: Uri, data: List<ExcelRowData>, technicianName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            ZipSecureFile.setMinInflateRatio(0.001)
            val workbook = WorkbookFactory.create(inputStream)
            inputStream?.close()

            val sheet = workbook.getSheetAt(0)

            // Aggiornamento testata (Riga 6 -> Indice 5)
            val row6 = sheet.getRow(5) ?: sheet.createRow(5)
            val cellA6 = row6.getCell(0) ?: row6.createCell(0)
            val cellC6 = row6.getCell(2) ?: row6.createCell(2)

            val today = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
            cellA6.setCellValue(today)
            cellC6.setCellValue("TECNICO: $technicianName")

            // Aggiornamento dati (Riga 8 -> Indice 7)
            var dataIndex = 0
            for (i in 7..sheet.lastRowNum) {
                val row = sheet.getRow(i) ?: continue
                val label = dataFormatter.formatCellValue(row.getCell(0)).trim()
                
                if (label.isNotEmpty()) {
                    if (dataIndex < data.size) {
                        val cellB = row.getCell(1) ?: row.createCell(1)
                        
                        // Preserva stili clonandoli
                        val oldStyle = cellB.cellStyle
                        if (oldStyle != null) {
                            val newStyle = workbook.createCellStyle()
                            newStyle.cloneStyleFrom(oldStyle)
                            cellB.cellStyle = newStyle
                        }
                        
                        val valueToWrite = data[dataIndex].value.ifEmpty { "N°" }
                        cellB.setCellValue(valueToWrite)
                        dataIndex++
                    }
                }
            }

            context.contentResolver.openOutputStream(uri, "rwt")?.use { outputStream ->
                workbook.write(outputStream)
                outputStream.flush()
            }
            workbook.close()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Crea un nuovo file a partire dal sample negli assets "SampleConsumo.xlsx".
     * Aggiorna data e nome tecnico nel nuovo file creato.
     */
    suspend fun createFromSample(uri: Uri, technicianName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            context.assets.open("SampleConsumo.xlsx").use { inputStream ->
                ZipSecureFile.setMinInflateRatio(0.001)
                val workbook = WorkbookFactory.create(inputStream)
                val sheet = workbook.getSheetAt(0)

                // Aggiornamento testata (Riga 6 -> Indice 5)
                val row6 = sheet.getRow(5) ?: sheet.createRow(5)
                val cellA6 = row6.getCell(0) ?: row6.createCell(0)
                val cellC6 = row6.getCell(2) ?: row6.createCell(2)

                val today = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                cellA6.setCellValue(today)
                cellC6.setCellValue("TECNICO: $technicianName")

                context.contentResolver.openOutputStream(uri, "rwt")?.use { outputStream ->
                    workbook.write(outputStream)
                    outputStream.flush()
                }
                workbook.close()
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
