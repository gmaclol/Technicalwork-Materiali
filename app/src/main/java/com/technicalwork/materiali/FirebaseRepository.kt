package com.technicalwork.materiali

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.os.BatteryManager
import android.location.LocationManager
import android.util.Log

class FirebaseRepository {

    companion object {
        private const val TAG = "TW_FirebaseRepo"
    }

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
    ): Boolean = withContext(Dispatchers.IO) {
        if (technicianName.isBlank() || company.isBlank()) return@withContext false

        // Controllo Connessione
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = cm?.getNetworkCapabilities(cm.activeNetwork)
        val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        if (!isConnected) {
            if (!isRetry) {
                SyncQueue().save(context, company, technicianName, materials, lat, lng, deviceId)
            }
            return@withContext false
        }

        // Garantisce l'autenticazione anonima Firebase prima di scrivere su Firestore
        AuthManager.ensureAuthenticated()

        try {
            val timestamp = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date())

            val masterList = AssetsHelper().loadMasterList(context, company)
            val normalizedMaster = masterList
                .filter { 
                    val trimmed = it.trim()
                    !trimmed.matches(separatorRegex) && !trimmed.matches(separatorExtraRegex) 
                }
                .map { it.trim().lowercase() }
                .toSet()

            val parser = StockParser()
            val filteredMaterials = materials.filter { item ->
                val label = item.label.trim()
                val isSeparator = label.matches(separatorRegex) || label.matches(separatorExtraRegex)
                if (isSeparator) return@filter false
                
                val isExtra = !normalizedMaster.contains(label.lowercase())
                if (isExtra) {
                    val stock = parser.parse(label, item.value)
                    val hasStock = stock.free > 0 || stock.used > 0
                    if (!hasStock) return@filter false
                }
                true
            }

            val materialiMap = filteredMaterials.associate { it.label to it.value }

            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val gpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false

            val currentMillis = System.currentTimeMillis()
            val data = hashMapOf<String, Any>(
                "tecnico" to technicianName,
                "ultimo_aggiornamento" to timestamp,
                "last_updated_at" to currentMillis,
                "last_updated_by" to "tecnico",
                "appalto" to company,
                "dispositivo" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                "versione_app" to "Ver ${BuildConfig.VERSION_NAME}",
                "materiali" to materialiMap,
                "ordine" to filteredMaterials.map { it.label }
            )

            if (batteryLevel != -1) {
                data["batteria"] = "$batteryLevel%"
            }
            data["gps_attivo"] = gpsEnabled

            // Aggiunta coordinate se presenti
            lat?.let { data["lat"] = it }
            lng?.let { data["lng"] = it }

            // 1. Salva documento principale del tecnico
            db.collection(company)
                .document(deviceId)
                .set(data)
                .await()

            // Memorizza il timestamp dell'ultimo sync riuscito per questa azienda nelle SharedPreferences locali
            val syncPrefs = context.getSharedPreferences("sync_meta", Context.MODE_PRIVATE)
            syncPrefs.edit().putLong("last_sync_timestamp_$company", currentMillis).apply()

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

            // Ottimizzazione: query solo per i documenti di questo device (prefix query)
            // Evita di scaricare TUTTA la collection e consumare la Firebase Quota.
            val allDocs = db.collection(company)
                .whereGreaterThanOrEqualTo(com.google.firebase.firestore.FieldPath.documentId(), prefix)
                .whereLessThan(com.google.firebase.firestore.FieldPath.documentId(), prefix + "\uf8ff")
                .get().await()
                
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
            Log.e(TAG, "Errore durante syncToFirestore per azienda $company: ${e.message}", e)
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
    suspend fun updateTechnicianName(deviceId: String, newName: String, companies: List<String>, lat: Double? = null, lng: Double? = null) = withContext(Dispatchers.IO) {
        AuthManager.ensureAuthenticated()
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

        // Aggiorna anche lo snapshot di oggi se esiste, così il rename è immediato anche per la data corrente
        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())

        allToUpdate.forEach { company ->
            try {
                // Documento live
                db.collection(company).document(deviceId).set(data, SetOptions.merge()).await()
                // Snapshot del giorno corrente (solo se esiste già, altrimenti lo skip)
                val todayDocId = "${deviceId}_$todayStr"
                val todaySnap = db.collection(company).document(todayDocId).get().await()
                if (todaySnap.exists()) {
                    db.collection(company).document(todayDocId).set(data, SetOptions.merge()).await()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore in updateTechnicianName per $company: ${e.message}", e)
            }
        }
        // Aggiorna il registro centrale con dot-notation per non sovrascrivere altri campi (es. pfsAreas)
        try {
            db.collection("settings").document("devices_names")
                .update(
                    "$deviceId.name", newName,
                    "$deviceId.updatedAt", System.currentTimeMillis()
                ).await()
        } catch (e: Exception) {
            try {
                // Fallback: verifica se il documento devices_names esiste già
                val docSnap = db.collection("settings").document("devices_names").get().await()
                if (!docSnap.exists()) {
                    // Crea il documento ex-novo
                    db.collection("settings").document("devices_names")
                        .set(hashMapOf(deviceId to hashMapOf("name" to newName, "updatedAt" to System.currentTimeMillis())))
                        .await()
                } else {
                    // Il documento esiste ma il field deviceId manca: riprova update (ora dovrebbe funzionare)
                    db.collection("settings").document("devices_names")
                        .update(
                            "$deviceId.name", newName,
                            "$deviceId.updatedAt", System.currentTimeMillis()
                        ).await()
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Errore fallback updateTechnicianName: ${e2.message}", e2)
            }
        }
    }

    /**
     * Aggiorna solo la versione dell'app nelle collezioni. Utile al riavvio post-update
     * per far comparire la nuova versione sulla dashboard senza aspettare un salvataggio materiali.
     */
    suspend fun updateAppVersionOnly(deviceId: String, companies: List<String>) = withContext(Dispatchers.IO) {
        AuthManager.ensureAuthenticated()
        val data = hashMapOf<String, Any>("versione_app" to "Ver ${BuildConfig.VERSION_NAME}")
        val allToUpdate = companies.toMutableList()
        if (!allToUpdate.contains("Consumo")) allToUpdate.add("Consumo")
        
        allToUpdate.forEach { company ->
            try {
                db.collection(company).document(deviceId).set(data, SetOptions.merge()).await()
            } catch (e: Exception) {
                Log.e(TAG, "Errore in updateAppVersionOnly per $company: ${e.message}", e)
            }
        }
    }

    /**
     * Aggiorna le aree PFS salvate per questo dispositivo
     */
    suspend fun updatePfsAreas(deviceId: String, pfsAreas: List<String>, techName: String? = null) = withContext(Dispatchers.IO) {
        AuthManager.ensureAuthenticated()
        val timestamp = System.currentTimeMillis()
        try {
            // Usa dot-notation per aggiornare solo pfsAreas senza cancellare il 'name'
            db.collection("settings").document("devices_names")
                .update(
                    "$deviceId.pfsAreas", pfsAreas,
                    "$deviceId.updatedAt", timestamp
                ).await()
        } catch (e: Exception) {
            try {
                val docSnap = db.collection("settings").document("devices_names").get().await()
                if (!docSnap.exists()) {
                    val devMap = hashMapOf<String, Any>(
                        "pfsAreas" to pfsAreas,
                        "updatedAt" to timestamp
                    )
                    if (techName != null) {
                        devMap["name"] = techName
                    }
                    db.collection("settings").document("devices_names")
                        .set(hashMapOf(deviceId to devMap))
                        .await()
                } else {
                    db.collection("settings").document("devices_names")
                        .update(
                            "$deviceId.pfsAreas", pfsAreas,
                            "$deviceId.updatedAt", timestamp
                        ).await()
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Errore fallback updatePfsAreas: ${e2.message}", e2)
            }
        }
    }

    /**
     * Aggiorna i dati di telemetria del dispositivo all'avvio in modo incrementale su Firestore.
     */
    suspend fun updateDeviceTelemetry(
        deviceId: String,
        companies: List<String>,
        deviceModel: String,
        appVersion: String,
        batteryLevel: Int,
        gpsEnabled: Boolean
    ) = withContext(Dispatchers.IO) {
        AuthManager.ensureAuthenticated()
        val timestamp = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date())
        Log.d(TAG, "Avvio updateDeviceTelemetry per $deviceId")
        val data = hashMapOf<String, Any>(
            "dispositivo" to deviceModel,
            "versione_app" to "Ver $appVersion",
            "gps_attivo" to gpsEnabled,
            "ultimo_aggiornamento" to timestamp
        )
        if (batteryLevel >= 0) {
            data["batteria"] = "$batteryLevel%"
        }

        val allToUpdate = companies.toMutableList()
        if (!allToUpdate.contains("Consumo")) allToUpdate.add("Consumo")

        allToUpdate.forEach { company ->
            try {
                db.collection(company).document(deviceId).set(data, SetOptions.merge()).await()
            } catch (e: Exception) {
                Log.e(TAG, "Errore in updateDeviceTelemetry per $company: ${e.message}", e)
            }
        }
    }
}

