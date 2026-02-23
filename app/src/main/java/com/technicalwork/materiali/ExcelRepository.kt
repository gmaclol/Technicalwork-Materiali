package com.technicalwork.materiali

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.InputStream

class ExcelRepository(private val context: Context) {

    private val dataFormatter = DataFormatter()

    /**
     * Legge un file Excel e ne restituisce il contenuto come lista di [ExcelRowData].
     * Se il formato non è valido o se c'è un errore, lancia un'eccezione.
     */
    suspend fun readExcelFile(uri: Uri): Result<List<ExcelRowData>> = withContext(Dispatchers.IO) {
        try {
            val dataList = mutableListOf<ExcelRowData>()
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            
            inputStream?.use { input ->
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
            }
            Result.success(dataList)
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
     */
    suspend fun createFromTemplate(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.assets.open("Sample.xlsx")
            val workbook = WorkbookFactory.create(inputStream)
            inputStream.close()

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
