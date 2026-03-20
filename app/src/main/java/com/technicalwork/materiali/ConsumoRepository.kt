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
                    
                    // Si ferma appena trova una riga di stop
                    if (isStopRow(label)) break

                    if (label.isNotEmpty()) {
                        val value = dataFormatter.formatCellValue(row.getCell(1)).trim()
                        val cleanValue = if (value == "N°" || value.isBlank()) "" else value.removePrefix("N° ").trim()
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
     * Gestisce l'aggiunta di nuove righe se l'utente ne ha create di extra.
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

            // 1. Aggiornamento righe esistenti
            var dataIndex = 0
            var stopRowIndex = -1
            for (i in 7..sheet.lastRowNum) {
                val row = sheet.getRow(i) ?: continue
                val labelFromFile = dataFormatter.formatCellValue(row.getCell(0)).trim()
                
                // Individua la riga di stop (NB:, ecc.)
                if (isStopRow(labelFromFile)) {
                    stopRowIndex = i
                    break
                }

                if (labelFromFile.isNotEmpty()) {
                    if (dataIndex < data.size) {
                        // Aggiorna il label (necessario per le righe "ALTRO:" riempite)
                        val cellA = row.getCell(0) ?: row.createCell(0)
                        cellA.setCellValue(data[dataIndex].label)

                        // Aggiorna il valore in colonna B
                        val cellB = row.getCell(1) ?: row.createCell(1)
                        val valueToWrite = if (data[dataIndex].value.isEmpty()) "N°" else "N° ${data[dataIndex].value}"
                        cellB.setCellValue(valueToWrite)
                        
                        dataIndex++
                    }
                }
            }

            // 2. Gestione righe extra aggiunte dall'utente (punto di inserimento: stopRowIndex)
            if (dataIndex < data.size && stopRowIndex != -1) {
                val numExtraRows = data.size - dataIndex
                
                // Fa spazio nel foglio spostando in basso il blocco finale delle note
                sheet.shiftRows(stopRowIndex, sheet.lastRowNum, numExtraRows)

                // Recupera stile e altezza dalla riga precedente (l'ultima "ALTRO:") per coerenza visiva
                val templateRow = sheet.getRow(stopRowIndex - 1)
                val templateHeight = templateRow?.height ?: sheet.defaultRowHeight
                val styleA = templateRow?.getCell(0)?.cellStyle
                val styleB = templateRow?.getCell(1)?.cellStyle

                for (extraIdx in 0 until numExtraRows) {
                    val newRow = sheet.createRow(stopRowIndex + extraIdx)
                    newRow.height = templateHeight

                    // Colonna A: Nuovo Label
                    val cellA = newRow.createCell(0)
                    cellA.setCellValue(data[dataIndex].label)
                    if (styleA != null) cellA.cellStyle = styleA

                    // Colonna B: Nuovo Valore
                    val cellB = newRow.createCell(1)
                    val valueToWrite = if (data[dataIndex].value.isEmpty()) "N°" else "N° ${data[dataIndex].value}"
                    cellB.setCellValue(valueToWrite)
                    if (styleB != null) cellB.cellStyle = styleB

                    dataIndex++
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

    private fun isStopRow(label: String): Boolean {
        val upperLabel = label.uppercase()
        return upperLabel.startsWith("NB:") ||
                upperLabel.startsWith("E INVIARE") ||
                upperLabel.startsWith("MI RACCOMANDO") ||
                upperLabel.startsWith("OGNI VOLTA")
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
