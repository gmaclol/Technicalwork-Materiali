package com.technicalwork.materiali

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri

/**
 * Gestisce tutte le interazioni con il file system, le Uri di Android (Storage Access Framework)
 * e i permessi persistenti, disaccoppiando questa logica dall'Activity.
 */
class FileStorageManager(private val context: Context) {

    private val contentResolver = context.contentResolver

    fun isUriAccessible(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { it.close() }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getFileNameFromUri(uri: Uri, defaultName: String = "Senza_Nome"): String {
        var name = defaultName
        if (uri.scheme == "file") {
            return uri.lastPathSegment ?: defaultName
        }
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst()) {
                    val displayName = cursor.getString(nameIndex)
                    if (!displayName.isNullOrEmpty()) {
                        name = displayName
                    }
                }
            }
        } catch (_: Exception) {}
        return name
    }

    fun takePersistableUriPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Se fallisce, l'Uri potrebbe non essere persistibile (es. cache temporanea)
        }
    }

    /**
     * Tenta di rinominare un file da un Uri.
     * Se fallisce (come spesso accade per Uri esterni da WhatsApp/Downloads),
     * lo copia in modo sicuro nei file pubblici (Documents) col nuovo nome e restituisce il nuovo Uri.
     */
    suspend fun safeRenameFile(originalUri: Uri, newFilename: String): Uri? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {

        // 1. Consolida i permessi
        try {
            contentResolver.takePersistableUriPermission(
                originalUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {}

        // 2. Assicura che il filename abbia estensione
        val finalFilename = if (newFilename.endsWith(".xlsx", ignoreCase = true) ||
                                newFilename.endsWith(".xls", ignoreCase = true)) {
            newFilename
        } else {
            "$newFilename.xlsx"
        }

        // 3. Primo tentativo: Rename nativo via DocumentFile
        try {
            val documentFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, originalUri)
            if (documentFile != null && documentFile.exists()) {
                val renamed = documentFile.renameTo(finalFilename)
                if (renamed) {
                    return@withContext originalUri
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FileStorageManager", "Rename nativo DocumentFile fallito", e)
        }

        // 4. Secondo tentativo (FALLBACK): Copia nella stessa cartella o in Documents
        try {
            val originalDoc = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, originalUri)
            val parentDoc = originalDoc?.parentFile
            var newUri: Uri? = null
            var scanPath: String? = null

            // 1 & 2. Tenta di creare il file nella stessa cartella dell'originale
            if (parentDoc != null && parentDoc.canWrite()) {
                val newDocFile = parentDoc.createFile("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", finalFilename)
                newUri = newDocFile?.uri
            }

            // 3. Fallback finale a DIRECTORY_DOCUMENTS se il parent non è accessibile
            if (newUri == null) {
                val publicDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS
                )
                if (!publicDir.exists()) publicDir.mkdirs()
                
                val destFile = java.io.File(publicDir, finalFilename)
                newUri = destFile.toUri()
                scanPath = destFile.absolutePath
            }

            // Copia effettiva del contenuto nel nuovo Uri
            contentResolver.openInputStream(originalUri)?.use { input ->
                contentResolver.openOutputStream(newUri!!)?.use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null

            // Scansione MediaStore sul nuovo file
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(scanPath ?: newUri.path), null, null
            )

            // Tentativo di eliminazione del file originale con vari metodi in ordine
            try {
                var deleted = false
                
                // 1) Tentativo via ContentResolver
                try {
                    val rows = contentResolver.delete(originalUri, null, null)
                    if (rows > 0) {
                        deleted = true
                        android.util.Log.d("FileStorageManager", "Originale eliminato via ContentResolver")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FileStorageManager", "Errore eliminazione via ContentResolver: ${e.message}", e)
                }

                // 2) Tentativo via DocumentFile
                if (!deleted) {
                    try {
                        if (originalDoc?.delete() == true) {
                            deleted = true
                            android.util.Log.d("FileStorageManager", "Originale eliminato via DocumentFile")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("FileStorageManager", "Errore eliminazione via DocumentFile: ${e.message}", e)
                    }
                }

                // 3) Tentativo via File (solo se schema file://)
                if (!deleted && originalUri.scheme == "file") {
                    try {
                        val path = originalUri.path
                        if (path != null && java.io.File(path).delete()) {
                            deleted = true
                            android.util.Log.d("FileStorageManager", "Originale eliminato via java.io.File")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("FileStorageManager", "Errore eliminazione via java.io.File: ${e.message}", e)
                    }
                }
                
                if (!deleted) {
                    android.util.Log.w("FileStorageManager", "Impossibile eliminare il file originale dopo la copia con nessuno dei metodi")
                }
            } catch (e: Exception) {
                android.util.Log.e("FileStorageManager", "Errore imprevisto durante la sequenza di eliminazione", e)
            }

            return@withContext newUri

        } catch (e: Exception) {
            android.util.Log.e("FileStorageManager", "Fallback copy-and-delete fallito", e)
            return@withContext null
        }
    }

    fun createIntentForSaveAs(
        fileName: String,
        mimeType: String = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    ): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
    }

    fun createIntentForOpenDocument(
        mimeType: String = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    ): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }
}
