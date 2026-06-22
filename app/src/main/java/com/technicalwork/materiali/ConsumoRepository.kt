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
                try {
                    val sheet = workbook.getSheetAt(0)

                    // Dati dalla riga 8 (indice 7) in poi
                    for (i in 7..sheet.lastRowNum) {
                        val row = sheet.getRow(i) ?: continue
                        val label = dataFormatter.formatCellValue(row.getCell(0)).trim()
                        
                        // Si ferma appena trova una riga di stop
                        if (isStopRow(label)) break

                        if (label.isNotEmpty()) {
                            val value = dataFormatter.formatCellValue(row.getCell(1)).trim()
                            val cleanValue = value.trim().let { v -> 
                                when { 
                                    v.isBlank() -> "" 
                                    v.equals("N°", ignoreCase = true) -> "" 
                                    v.startsWith("N°", ignoreCase = true) -> v.substring(2).trim() 
                                    else -> v 
                                } 
                            }
                            dataList.add(ExcelRowData(label, cleanValue))
                        }
                    }
                    Result.success(dataList)
                } finally {
                    workbook.close()
                }
            } ?: Result.failure(Exception("Impossibile aprire il file"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Salva i dati di consumo utilizzando SampleConsumo.xlsx come template pulito.
     * Risolve i problemi di formattazione e shiftRows sovrascrivendo il file di destinazione.
     */
    suspend fun saveConsumoFile(uri: Uri, data: List<ExcelRowData>, technicianName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 1. Apri SampleConsumo.xlsx dagli assets come base fresca
            context.assets.open("SampleConsumo.xlsx").use { inputStream ->
                ZipSecureFile.setMinInflateRatio(0.001)
                val workbook = WorkbookFactory.create(inputStream)
                try {
                    val sheet = workbook.getSheetAt(0)

                    // 2. Aggiorna testata (Riga 6 -> Indice 5)
                    val row6 = sheet.getRow(5) ?: sheet.createRow(5)
                    val cellA6 = row6.getCell(0) ?: row6.createCell(0)
                    val cellC6 = row6.getCell(2) ?: row6.createCell(2)

                    val today = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                    cellA6.setCellValue(today)
                    cellC6.setCellValue("TECNICO: $technicianName")

                    // 3. Creazione mappa label -> value per lookup veloce e set per tracciare le extra
                    val dataMap = data.associateBy({ it.label }, { it.value })
                    val remainingLabels = data.map { it.label }.toMutableSet()

                    // 4. Scorrimento righe standard nel template (dalla riga 8 -> indice 7)
                    var stopRowIndex = -1
                    for (i in 7..sheet.lastRowNum) {
                        val row = sheet.getRow(i) ?: continue
                        val label = dataFormatter.formatCellValue(row.getCell(0)).trim()
                        
                        if (isStopRow(label)) {
                            stopRowIndex = i
                            break
                        }

                        if (label.isNotEmpty()) {
                            val cellB = row.getCell(1) ?: row.createCell(1)
                            if (dataMap.containsKey(label)) {
                                // Aggiorna il valore se presente nei dati
                                val value = dataMap[label] ?: ""
                                val valueToWrite = if (value.isEmpty()) "N°" else "N° $value"
                                cellB.setCellValue(valueToWrite)
                                remainingLabels.remove(label)
                            } else {
                                // Se un dato standard non è presente nella lista UI, lo resettiamo a "N°"
                                cellB.setCellValue("N°")
                            }
                        }
                    }

                    // 5. Inserimento righe extra (quelle rimaste in remainingLabels)
                    val extraData = data.filter { it.label in remainingLabels }
                    
                    if (extraData.isNotEmpty() && stopRowIndex != -1) {
                        val numExtraRows = extraData.size
                        
                        // Recupera stile e altezza dalla riga immediatamente precedente allo stop (l'ultima standard)
                        val templateRow = sheet.getRow(stopRowIndex - 1)
                        val templateHeight = templateRow?.height ?: sheet.defaultRowHeight

                        // Prepariamo gli stili clonati dal template prima di fare shift
                        val styleA = workbook.createCellStyle().apply { templateRow?.getCell(0)?.cellStyle?.let { cloneStyleFrom(it) } }
                        val styleB = workbook.createCellStyle().apply { templateRow?.getCell(1)?.cellStyle?.let { cloneStyleFrom(it) } }
                        val styleC = workbook.createCellStyle().apply { templateRow?.getCell(2)?.cellStyle?.let { cloneStyleFrom(it) } }
                        val styleD = workbook.createCellStyle().apply { templateRow?.getCell(3)?.cellStyle?.let { cloneStyleFrom(it) } }

                        // Sposta in basso il blocco finale (note/footer) per fare spazio alle nuove righe
                        sheet.shiftRows(stopRowIndex, sheet.lastRowNum, numExtraRows)

                        for (idx in extraData.indices) {
                            val item = extraData[idx]
                            val newRow = sheet.createRow(stopRowIndex + idx)
                            newRow.height = templateHeight

                            // Colonna A: Label
                            newRow.createCell(0).apply { 
                                setCellValue(item.label)
                                cellStyle = styleA 
                            }
                            // Colonna B: Valore
                            newRow.createCell(1).apply { 
                                val valueToWrite = if (item.value.isEmpty()) "N°" else "N° ${item.value}"
                                setCellValue(valueToWrite)
                                cellStyle = styleB 
                            }
                            // Colonne C e D: Vuote (N°) come da template
                            newRow.createCell(2).apply { setCellValue("N°"); cellStyle = styleC }
                            newRow.createCell(3).apply { setCellValue("N°"); cellStyle = styleD }
                        }
                    }

                    // 6. Salva nel file di destinazione finale sovrascrivendo l'esistente
                    context.contentResolver.openOutputStream(uri, "rwt")?.use { outputStream ->
                        workbook.write(outputStream)
                        outputStream.flush()
                    }
                    Result.success(Unit)
                } finally {
                    workbook.close()
                }
            }
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
                try {
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
                    Result.success(Unit)
                } finally {
                    workbook.close()
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
