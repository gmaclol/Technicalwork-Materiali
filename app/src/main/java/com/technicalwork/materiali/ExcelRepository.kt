package com.technicalwork.materiali

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory

class ExcelRepository(private val context: Context) {

    private val dataFormatter = DataFormatter()
    private val separatorRegex = Regex("^::.*::$")
    private val separatorExtraRegex = Regex("^;;.*;;$")

    /**
     * Legge un file Excel e ne restituisce il contenuto come lista di [ExcelRowData].
     * Applica il merge con la lista specifica per [company] (o fallback).
     */
    suspend fun readExcelFile(uri: Uri, company: String? = null): Result<List<ExcelRowData>> = withContext(Dispatchers.IO) {
        try {
            val dataList = mutableListOf<ExcelRowData>()
            val inputStream = context.contentResolver.openInputStream(uri)
            
            inputStream?.use { input ->
                ZipSecureFile.setMinInflateRatio(0.001)
                val workbook = WorkbookFactory.create(input)
                val sheet = workbook.getSheetAt(0)
                
                // Controllo Header riga 3 colonna A
                val headerRow = sheet.getRow(3)
                val headerText = dataFormatter.formatCellValue(headerRow?.getCell(0))
                
                if (!headerText.contains("MATERIALE DI CONSUMO", ignoreCase = true)) {
                    workbook.close()
                    return@withContext Result.failure(Exception("Formato file non valido"))
                }
                
                // 1) Carica masterList e scansiona per trovare l'inizio dati
                val masterList = AssetsHelper().loadMasterList(context, company)
                // Escludiamo i separatori dal set per la ricerca del primo materiale reale
                val masterListSet = masterList
                    .filter { !it.trim().matches(separatorRegex) && !it.trim().matches(separatorExtraRegex) }
                    .map { it.trim().lowercase() }
                    .toSet()

                var startRowIndex = 4
                for (i in 4..sheet.lastRowNum) {
                    val row = sheet.getRow(i) ?: continue
                    val cellText = dataFormatter.formatCellValue(row.getCell(0)).trim()
                    if (cellText.isNotEmpty()) {
                        startRowIndex = i
                        break
                    }
                }

                // Parsing righe partendo da startRowIndex
                for (i in startRowIndex..sheet.lastRowNum) {
                    val row = sheet.getRow(i) ?: continue
                    val label = dataFormatter.formatCellValue(row.getCell(0))
                    val value = dataFormatter.formatCellValue(row.getCell(1))
                    
                    // 2) Gestione valori zero
                    val cleanValue = if (value.trim() == "0") "" else value.trim()
                    dataList.add(ExcelRowData(label, cleanValue))
                }
                workbook.close()

                // Applica il merge (che gestisce i separatori dalla masterList)
                val techPairs = dataList.map { Pair(it.label, it.value) }
                val mergedPairs = MaterialMerger().merge(techPairs, masterList)
                val finalDataList = mergedPairs.map { ExcelRowData(it.first, it.second) }

                return@withContext Result.success(finalDataList)
            }
            Result.failure(Exception("Impossibile aprire il file"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Salva i dati correnti sul file Excel indicato dall'Uri.
     */
    suspend fun saveExcelFile(uri: Uri, data: List<ExcelRowData>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            ZipSecureFile.setMinInflateRatio(0.001)
            val workbook = WorkbookFactory.create(inputStream)
            inputStream?.close()
            
            val sheet = workbook.getSheetAt(0)
            val templateRow = if (sheet.lastRowNum >= 6) sheet.getRow(6) else if (sheet.lastRowNum >= 4) sheet.getRow(4) else sheet.getRow(0)
            
            // Popolamento dei dati partendo dalla riga 4 (indice base 0)
            // SALTA i separatori
            var excelRowIndex = 4
            data.forEach { rowData ->
                if (rowData.label.trim().matches(separatorRegex) || rowData.label.trim().matches(separatorExtraRegex)) {
                    return@forEach // Salta scrittura separatore
                }

                val row = sheet.getRow(excelRowIndex) ?: sheet.createRow(excelRowIndex).apply {
                    if (templateRow != null) height = templateRow.height
                }
                
                val cell0 = row.getCell(0) ?: row.createCell(0)
                val cell1 = row.getCell(1) ?: row.createCell(1)
                
                // Clonazione degli stili dalla row template
                if (templateRow != null) {
                    val tempCellStyle0 = templateRow.getCell(0)?.cellStyle
                    if (tempCellStyle0 != null) {
                        val style0 = workbook.createCellStyle()
                        style0.cloneStyleFrom(tempCellStyle0)
                        val font0 = workbook.createFont()
                        val tempFont0 = workbook.getFontAt(tempCellStyle0.fontIndex)
                        font0.fontHeightInPoints = tempFont0.fontHeightInPoints
                        font0.fontName = tempFont0.fontName
                        font0.bold = true
                        style0.setFont(font0)
                        cell0.cellStyle = style0
                    }
                    
                    val tempCellStyle1 = templateRow.getCell(1)?.cellStyle
                    if (tempCellStyle1 != null) {
                        val style1 = workbook.createCellStyle()
                        style1.cloneStyleFrom(tempCellStyle1)
                        cell1.cellStyle = style1
                    }
                }
                
                updateCellValue(cell0, rowData.label)
                updateCellValue(cell1, rowData.value)
                excelRowIndex++
            }
            
            // Rimozione righe eccedenti in basso
            val startDeleteIndex = excelRowIndex
            val lastRowNum = sheet.lastRowNum
            if (lastRowNum >= startDeleteIndex) {
                for (i in (lastRowNum downTo startDeleteIndex)) {
                    val row = sheet.getRow(i)
                    if (row != null) sheet.removeRow(row)
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
     * Ricrea il file a partire dal Sample fornito negli assets dell'app.
     * Popola con la lista materiali specifica per [company].
     */
    suspend fun createFromTemplate(uri: Uri, company: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.assets.open("Sample.xlsx")
            ZipSecureFile.setMinInflateRatio(0.001)
            val workbook = WorkbookFactory.create(inputStream)
            inputStream.close()
            val sheet = workbook.getSheetAt(0)

            // Prendo la riga 4 come riferimento per gli stili prima di cancellare tutto
            val referenceRow = sheet.getRow(4)
            val templateHeight = referenceRow?.height ?: -1
            val templateStyle0 = referenceRow?.getCell(0)?.cellStyle?.let {
                workbook.createCellStyle().apply { cloneStyleFrom(it) }
            }
            val templateStyle1 = referenceRow?.getCell(1)?.cellStyle?.let {
                workbook.createCellStyle().apply { cloneStyleFrom(it) }
            }

            // 1. Cancella tutte le righe esistenti dalla riga 4 in poi
            val lastRow = sheet.lastRowNum
            if (lastRow >= 4) {
                for (i in lastRow downTo 4) {
                    sheet.getRow(i)?.let { sheet.removeRow(it) }
                }
            }

            // Genera le righe dalla 4 in poi leggendo il file .txt specifico (o fallback)
            val masterList = AssetsHelper().loadMasterList(context, company)
            var excelRowIndex = 4
            masterList.forEach { name ->
                if (name.trim().matches(separatorRegex) || name.trim().matches(separatorExtraRegex)) {
                    return@forEach // Salta separatore
                }

                val row = sheet.getRow(excelRowIndex) ?: sheet.createRow(excelRowIndex)
                
                val cell0 = row.createCell(0)
                val cell1 = row.createCell(1)
                
                cell0.setCellValue(name)
                cell1.setCellValue("")
                
                // Copia gli stili dalla riga di esempio (riga 4 del template)
                templateStyle0?.let { cell0.cellStyle = it }
                templateStyle1?.let { cell1.cellStyle = it }
                if (templateHeight != (-1).toShort()) row.height = templateHeight.toShort()

                excelRowIndex++
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
     * Resetta il file basandosi sul template Sample.xlsx e ricaricando la masterList.
     */
    suspend fun resetToTemplate(uri: Uri, company: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.assets.open("Sample.xlsx")
            ZipSecureFile.setMinInflateRatio(0.001)
            val workbook = WorkbookFactory.create(inputStream)
            inputStream.close()
            val sheet = workbook.getSheetAt(0)

            // Prendo la riga 4 come riferimento per gli stili (prima di cancellare)
            val referenceRow = sheet.getRow(4)
            val templateHeight = referenceRow?.height ?: (-1).toShort()
            val templateStyle0 = referenceRow?.getCell(0)?.cellStyle
            val templateStyle1 = referenceRow?.getCell(1)?.cellStyle

            // 1. Cancella tutte le righe esistenti dalla riga 4 in poi
            val lastRow = sheet.lastRowNum
            if (lastRow >= 4) {
                for (i in lastRow downTo 4) {
                    sheet.getRow(i)?.let { sheet.removeRow(it) }
                }
            }

            // 2. Carica masterList
            val masterList = AssetsHelper().loadMasterList(context, company)
            var excelRowIndex = 4

            // 3. Scrive le righe saltando i separatori
            masterList.forEach { name ->
                if (name.trim().matches(separatorRegex) || name.trim().matches(separatorExtraRegex)) {
                    return@forEach
                }

                val row = sheet.createRow(excelRowIndex)
                if (templateHeight != (-1).toShort()) row.height = templateHeight

                val cell0 = row.createCell(0)
                val cell1 = row.createCell(1)

                templateStyle0?.let {
                    val newStyle = workbook.createCellStyle()
                    newStyle.cloneStyleFrom(it)
                    cell0.cellStyle = newStyle
                }
                templateStyle1?.let {
                    val newStyle = workbook.createCellStyle()
                    newStyle.cloneStyleFrom(it)
                    cell1.cellStyle = newStyle
                }

                cell0.setCellValue(name)
                cell1.setCellValue("") // Cella vuota
                
                excelRowIndex++
            }

            // 4. Sovrascrive il file
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

    private fun updateCellValue(cell: Cell, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == "0") {
            cell.setBlank()
        } else {
            val dv = trimmed.toDoubleOrNull()
            if (dv != null) cell.setCellValue(dv) else cell.setCellValue(trimmed)
        }
    }
}
