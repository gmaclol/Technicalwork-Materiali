package com.technicalwork.materiali

import android.content.Context
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class ExcelWriter {

    /**
     * Scrive i materiali forniti in un file Excel basato sul template Sample.xlsx.
     * 
     * Procedura:
     * 1) Copia il template dagli assets alla cache.
     * 2) Apre il file con supporto per file compressi (ZipSecureFile).
     * 3) Pulisce le righe esistenti dall'indice 4 in poi.
     * 4) Inserisce i nuovi dati (Nome in colonna 0, Quantità in colonna 1).
     * 5) Salva e restituisce il file.
     */
    fun writeOutput(context: Context, materials: List<Pair<String, String>>): File {
        // 1) Copia Sample.xlsx dagli assets in cache usando AssetsHelper
        val assetsHelper = AssetsHelper()
        val targetFile = assetsHelper.copyTemplate(context)

        try {
            // 2) Imposto il ratio di sicurezza prima dell'apertura
            ZipSecureFile.setMinInflateRatio(0.001)

            // Apre il file copiato
            val fis = FileInputStream(targetFile)
            val workbook = WorkbookFactory.create(fis)
            fis.close()

            // 3) Prende il primo foglio
            val sheet = workbook.getSheetAt(0)
            val templateRow = sheet.getRow(4)

            // 4) Cancella tutte le righe dall'indice 4 in poi
            val lastRow = sheet.lastRowNum
            if (lastRow >= 4) {
                for (i in 4..lastRow) {
                    val row = sheet.getRow(i)
                    if (row != null) {
                        sheet.removeRow(row)
                    }
                }
            }

            // 5) Per ogni elemento della lista crea una nuova riga partendo dall'indice 4
            materials.forEachIndexed { index, pair ->
                val rowIndex = index + 4
                val row = sheet.createRow(rowIndex)
                templateRow?.let { row.height = it.height }
                
                val cell0 = row.createCell(0)
                val cell1 = row.createCell(1)
                
                templateRow?.getCell(0)?.let { 
                    val newStyle = workbook.createCellStyle()
                    newStyle.cloneStyleFrom(it.cellStyle)
                    cell0.cellStyle = newStyle 
                }
                templateRow?.getCell(1)?.let {
                    val newStyle = workbook.createCellStyle()
                    newStyle.cloneStyleFrom(it.cellStyle)
                    cell1.cellStyle = newStyle
                }
                
                cell0.setCellValue(pair.first)
                cell1.setCellValue(pair.second)
            }

            // 6) Salva il file e lo restituisce
            val fos = FileOutputStream(targetFile)
            workbook.write(fos)
            fos.close()
            workbook.close()

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return targetFile
    }
}
