package com.technicalwork.materiali

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.InputStream

class ExcelRepository(private val context: Context) {

    private val dataFormatter = DataFormatter()

    /**
     * Legge un file Excel e ne restituisce il contenuto come lista di [ExcelRowData].
     * Applica il merge con la lista specifica per [company] (o fallback).
     */
    suspend fun readExcelFile(uri: Uri, company: String? = null): Result<List<ExcelRowData>> = withContext(Dispatchers.IO) {
        Log.d("REPO_DEBUG", "Inizio lettura file")
        try {
            val dataList = mutableListOf<ExcelRowData>()
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            
            inputStream?.use { input ->
                Log.d("REPO_DEBUG", "InputStream aperto")
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
                
                // Parsing righe partendo dalla 4
                for (i in 4..sheet.lastRowNum) {
                    val row = sheet.getRow(i) ?: continue
                    val label = dataFormatter.formatCellValue(row.getCell(0))
                    val value = dataFormatter.formatCellValue(row.getCell(1))
                    dataList.add(ExcelRowData(label, value))
                }
                workbook.close()
                Log.d("REPO_DEBUG", "Righe lette: ${dataList.size}")

                // Applica il merge con la lista specifica (o fallback lista.txt)
                Log.d("REPO_DEBUG", "Chiamo loadMasterList con company: $company")
                val masterList = AssetsHelper().loadMasterList(context, company)
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
            data.forEachIndexed { index, rowData ->
                val excelRowIndex = index + 4
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
            }
            
            // Rimozione righe eccedenti in basso
            val startDeleteIndex = data.size + 4
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

            // Prendo la riga 4 come riferimento per gli stili
            val styleRow = sheet.getRow(4)

            // Genera le righe dalla 4 in poi leggendo il file .txt specifico (o fallback)
            val masterList = AssetsHelper().loadMasterList(context, company)
            masterList.forEachIndexed { index, name ->
                val rowIndex = index + 4
                val row = sheet.getRow(rowIndex) ?: sheet.createRow(rowIndex)
                
                val cell0 = row.createCell(0)
                val cell1 = row.createCell(1)
                
                cell0.setCellValue(name)
                cell1.setCellValue("")
                
                // Copia gli stili dalla riga di esempio (riga 4 del template)
                styleRow?.let { template ->
                    template.getCell(0)?.let { cell0.cellStyle = it.cellStyle }
                    template.getCell(1)?.let { cell1.cellStyle = it.cellStyle }
                    row.height = template.height
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

    private fun updateCellValue(cell: Cell, value: String) {
        val doubleValue = value.toDoubleOrNull()
        if (doubleValue != null) {
            cell.setCellValue(doubleValue)
        } else {
            cell.setCellValue(value)
        }
    }
}
