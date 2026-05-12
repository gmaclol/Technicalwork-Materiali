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
        isRetry: Boolean = false,
        deviceId: String
    ): Boolean {
        if (technicianName.isBlank() || company.isBlank()) return false

        // Controllo Connessione
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        if (!isConnected) {
            if (!isRetry) {
                SyncQueue().save(context, company, technicianName, materials, lat, lng, deviceId)
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
                "dispositivo" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                "versione_app" to "Ver ${BuildConfig.VERSION_NAME}",
                "materiali" to materialiMap,
                "ordine" to materials.filter { !it.label.trim().matches(separatorRegex) && !it.label.trim().matches(separatorExtraRegex) }.map { it.label }
            )

            // Aggiunta coordinate se presenti
            lat?.let { data["lat"] = it }
            lng?.let { data["lng"] = it }

            // 1. Salva documento principale del tecnico
            db.collection(company)
                .document(deviceId)
                .set(data)
                .await()

            // --- LOGICA SNAPSHOT ---
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            // 2. Istantanea del giorno corrente
            val todayStr = sdf.format(Date())
            val todayDocId = "${deviceId}_$todayStr"

            // Aggiorna costantemente lo snapshot di oggi per fissare la versione finale della giornata
            db.collection(company).document(todayDocId).set(data).await()

            // 3. Eliminazione snapshot più vecchi di 7 giorni
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -7)
            val sevenDaysAgoStr = sdf.format(cal.time)
            val prefix = "${deviceId}_"

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
                SyncQueue().save(context, company, technicianName, materials, lat, lng, deviceId)
            }
            false
        }
    }

    /**
     * Aggiorna solo il nome del tecnico in tutte le collezioni principali
     * e nel registro centrale devices_names con timestamp. Includendo i metadata primari.
     */
    fun updateTechnicianName(deviceId: String, newName: String, companies: List<String>, lat: Double? = null, lng: Double? = null) {
        val timestamp = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date())
        val data = hashMapOf<String, Any>(
            "tecnico" to newName,
            "dispositivo" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "versione_app" to "Ver ${BuildConfig.VERSION_NAME}",
            "ultimo_aggiornamento" to timestamp
        )
        lat?.let { data["lat"] = it }
        lng?.let { data["lng"] = it }

        val allToUpdate = companies.toMutableList()
        if (!allToUpdate.contains("Consumo")) allToUpdate.add("Consumo")

        allToUpdate.forEach { company ->
            try {
                db.collection(company).document(deviceId).set(data, SetOptions.merge())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // Aggiorna il registro centrale con dot-notation per non sovrascrivere altri campi (es. pfsAreas)
        try {
            db.collection("settings").document("devices_names")
                .update(
                    "$deviceId.name", newName,
                    "$deviceId.updatedAt", System.currentTimeMillis()
                )
        } catch (e: Exception) {
            // Se il documento o il campo non esistono ancora, usiamo il set merge come fallback
            try {
                val nameData = hashMapOf<String, Any>(
                    deviceId to hashMapOf(
                        "name" to newName,
                        "updatedAt" to System.currentTimeMillis()
                    )
                )
                db.collection("settings").document("devices_names")
                    .set(nameData, SetOptions.merge())
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    /**
     * Aggiorna le aree PFS salvate per questo dispositivo
     */
    fun updatePfsAreas(deviceId: String, pfsAreas: List<String>) {
        val timestamp = System.currentTimeMillis()
        try {
            // Usa dot-notation per aggiornare solo pfsAreas senza cancellare il 'name'
            db.collection("settings").document("devices_names")
                .update(
                    "$deviceId.pfsAreas", pfsAreas,
                    "$deviceId.updatedAt", timestamp
                )
        } catch (e: Exception) {
            // Se fallisce (magari il campo deviceId non esiste ancora), usiamo il set merge
            try {
                val nameData = hashMapOf<String, Any>(
                    deviceId to hashMapOf(
                        "pfsAreas" to pfsAreas,
                        "updatedAt" to timestamp
                    )
                )
                db.collection("settings").document("devices_names")
                    .set(nameData, SetOptions.merge())
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }
}
