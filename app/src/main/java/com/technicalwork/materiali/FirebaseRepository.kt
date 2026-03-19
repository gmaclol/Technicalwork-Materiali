package com.technicalwork.materiali

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class FirebaseRepository {

    private val db = Firebase.firestore
    private val separatorRegex = Regex("^::.*::$")
    private val separatorExtraRegex = Regex("^;;.*;;$")

    /**
     * Sincronizza i dati dei materiali su Firestore.
     * Crea/Aggiorna un documento nella collection [company] con ID [technicianName].
     */
    suspend fun syncToFirestore(
        context: Context,
        company: String,
        technicianName: String,
        materials: List<ExcelRowData>,
        lat: Double? = null,
        lng: Double? = null,
        isRetry: Boolean = false
    ): Boolean {
        if (technicianName.isBlank() || company.isBlank()) return false

        // Controllo Connessione
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        if (!isConnected) {
            if (!isRetry) {
                SyncQueue().save(context, company, technicianName, materials, lat, lng)
            }
            return false
        }

        return try {
            val timestamp = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date())

            // Filtra i materiali escludendo i separatori e crea la mappa
            val materialiMap = materials
                .filter { item ->
                    val label = item.label.trim()
                    !label.matches(separatorRegex) && !label.matches(separatorExtraRegex)
                }
                .associate { it.label to it.value }

            val data = hashMapOf<String, Any>(
                "tecnico" to technicianName,
                "ultimo_aggiornamento" to timestamp,
                "appalto" to company,
                "materiali" to materialiMap,
                "ordine" to materials.filter { !it.label.trim().matches(separatorRegex) && !it.label.trim().matches(separatorExtraRegex) }.map { it.label }
            )

            // Aggiunta coordinate se presenti
            lat?.let { data["lat"] = it }
            lng?.let { data["lng"] = it }

            // 1. Salva documento principale del tecnico
            db.collection(company)
                .document(technicianName)
                .set(data, SetOptions.merge())
                .await()

            // --- LOGICA SNAPSHOT ---
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            // 2. Controllo snapshot di ieri
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -1)
            val yesterdayStr = sdf.format(cal.time)
            val yesterdayDocId = "${technicianName}_$yesterdayStr"

            val snapshotRef = db.collection(company).document(yesterdayDocId)
            val snapshotDoc = snapshotRef.get().await()

            if (!snapshotDoc.exists()) {
                // Crea lo snapshot con i dati attuali
                snapshotRef.set(data, SetOptions.merge()).await()
            }

            // 3. Eliminazione snapshot più vecchi di 7 giorni
            cal.time = Date() // torna a oggi
            cal.add(Calendar.DAY_OF_YEAR, -7)
            val sevenDaysAgoStr = sdf.format(cal.time)
            val prefix = "${technicianName}_"

            val allDocs = db.collection(company).get().await()
            for (doc in allDocs.documents) {
                val id = doc.id
                if (id.startsWith(prefix)) {
                    val datePart = id.removePrefix(prefix)
                    // Verifica se il formato è yyyy-MM-dd e se è più vecchio di 7 giorni
                    if (datePart.matches(Regex("""^\d{4}-\d{2}-\d{2}$"""))) {
                        if (datePart < sevenDaysAgoStr) {
                            doc.reference.delete().await()
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            if (!isRetry) {
                SyncQueue().save(context, company, technicianName, materials, lat, lng)
            }
            false
        }
    }
}
