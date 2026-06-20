package com.technicalwork.materiali

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Gestore centralizzato per la sincronizzazione totale dell'app.
 * Scarica le liste aggiornate, rileva il GPS e invia lo stato di tutti i file a Firestore.
 */
class SyncManager(private val context: Context) {

    companion object {
        private const val TAG = "TW_SyncManager"

        fun getLastLocationHelper(context: Context): Pair<Double?, Double?> {
            return try {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    return null to null
                }
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) 
                          ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                loc?.latitude to loc?.longitude
            } catch (e: Exception) {
                null to null
            }
        }
    }

    fun performFullSync(scope: CoroutineScope, passedLat: Double? = null, passedLng: Double? = null, isFullSync: Boolean = true) {
        scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Avvio performFullSync: isFullSync=$isFullSync")
            val settingsRepo = SettingsRepository(context)
            val configManager = ConfigManager(context)
            val techName = settingsRepo.technicianName ?: run {
                Log.w(TAG, "Annullato: Nome tecnico non impostato")
                return@launch
            }
            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            val firebaseRepo = FirebaseRepository()
            
            // 0. Controllo Forza Aggiornamento Liste/Config da Dashboard (Firestore)
            try {
                Log.d(TAG, "Verifica presenza di 'Forza Aggiornamento' liste da Firestore")
                val db = Firebase.firestore
                val snap = db.collection("settings").document("dashboard").get().await()
                if (snap.exists()) {
                    val remoteTimestamp = snap.getLong("forceListUpdate") ?: 0L
                    val syncPrefs = context.getSharedPreferences("sync_meta", Context.MODE_PRIVATE)
                    val localTimestamp = syncPrefs.getLong("last_force_list_update", 0L)
                    
                    if (remoteTimestamp > localTimestamp) {
                        Log.i(TAG, "Rilevato 'Forza Aggiornamento' da Dashboard (remoto=$remoteTimestamp > locale=$localTimestamp). Eseguo fetch forzato da GitHub!")
                        
                        // Forza il fetch immediato da GitHub indipendentemente da isFullSync
                        configManager.fetchRemoteConfig()
                        configManager.fetchRemoteRegionsJson()
                        ListUpdater().syncLists(context, configManager.getCompanies(), configManager.getPfsAreas())
                        
                        // Aggiorna il timestamp locale per evitare fetch continui
                        syncPrefs.edit().putLong("last_force_list_update", remoteTimestamp).apply()
                        // Segna che le liste sono state aggiornate per la ri-validazione di MainActivity
                        syncPrefs.edit().putLong("lists_updated_at", System.currentTimeMillis()).apply()
                        Log.i(TAG, "Fetch forzato da GitHub completato con successo. Timestamp aggiornato.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore durante il controllo del Forza Aggiornamento da Firestore: ${e.message}", e)
            }
            
            // Eseguiamo il flush dei PFS offline accumulati in precedenza
            try {
                Log.d(TAG, "Tentativo di flush di PfsSyncQueue")
                PfsSyncQueue().flush(context)
            } catch (e: Exception) {
                Log.e(TAG, "Errore durante il flush di PfsSyncQueue: ${e.message}", e)
            }

            // Eseguiamo il flush dei materiali offline accumulati in precedenza
            try {
                Log.d(TAG, "Tentativo di flush di SyncQueue")
                SyncQueue().flush(context, firebaseRepo)
            } catch (e: Exception) {
                Log.e(TAG, "Errore durante il flush di SyncQueue: ${e.message}", e)
            }

            // 2. Rilevamento Posizione
            // Il worker pre-fetcha con LocationManager (veloce ma spesso null).
            // Se mancante, usa FusedLocationProviderClient (cache piu' affidabile di Play Services).
            var lat = passedLat
            var lng = passedLng
            if (lat == null || lng == null) {
                try {
                    val fusedClient = com.google.android.gms.location.LocationServices
                        .getFusedLocationProviderClient(context)
                    val loc = com.google.android.gms.tasks.Tasks.await(
                        fusedClient.lastLocation, 3, java.util.concurrent.TimeUnit.SECONDS
                    )
                    if (loc != null) {
                        lat = loc.latitude
                        lng = loc.longitude
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "FusedLocation fallito, provo LocationManager raw: ${e.message}")
                    // Fallback finale a LocationManager raw
                    val (fbLat, fbLng) = getLastLocationHelper(context)
                    lat = fbLat
                    lng = fbLng
                }
            }
            
            if (!isFullSync) {
                // LIGHT SYNC: Aggiorna solo presenza e posizione per non consumare letture Excel e scritture massive
                Log.d(TAG, "Esecuzione Light Sync per $techName (lat=$lat, lng=$lng)")
                firebaseRepo.updateTechnicianName(deviceId, techName, configManager.getCompanies(), lat, lng)
                return@launch
            }

            Log.d(TAG, "Esecuzione Full Sync per $techName")

            // 1. Fetch Configurazione Remota e Liste (GitHub) - Spostato qui per caricarle SOLO in Full Sync
            try {
                Log.d(TAG, "Download configurazione e regioni da GitHub")
                configManager.fetchRemoteConfig()
                configManager.fetchRemoteRegionsJson()
                ListUpdater().syncLists(context, configManager.getCompanies(), configManager.getPfsAreas())
            } catch (e: Exception) {
                Log.e(TAG, "Errore nel download delle liste/regioni da GitHub: ${e.message}", e)
            }

            // 3. Sync di tutti i file Excel (Aziende e Consumo)
            val excelRepo = ExcelRepository(context)
            val fileStorageManager = FileStorageManager(context)
            
            // Aggiorna versione app solo se effettivamente cambiata dall'ultimo sync
            val syncPrefs = context.getSharedPreferences("sync_meta", Context.MODE_PRIVATE)
            val lastSyncedVersion = syncPrefs.getString("last_synced_version", null)
            val currentVersion = "Ver ${BuildConfig.VERSION_NAME}"
            if (lastSyncedVersion != currentVersion) {
                firebaseRepo.updateAppVersionOnly(deviceId, configManager.getCompanies())
                syncPrefs.edit().putString("last_synced_version", currentVersion).apply()
            }

            // Aziende
            configManager.getCompanies().forEach { company ->
                settingsRepo.getCompanyFileUri(company)?.let { uriString ->
                    val uri = Uri.parse(uriString)
                    if (fileStorageManager.isUriAccessible(uri)) {
                        val localFile = try {
                            val docFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                            if (docFile != null && docFile.exists()) {
                                // Tenta di ottenere la data fisica del file
                                docFile.lastModified()
                            } else 0L
                        } catch (e: Exception) { 0L }

                        val lastSyncTime = syncPrefs.getLong("last_sync_timestamp_$company", 0L)
                        var hasOfflineChanges = localFile > (lastSyncTime + 2000L)

                        // 3A. Controlla se la Dashboard Admin ha modificato i dati
                        try {
                            val db = com.google.firebase.ktx.Firebase.firestore
                            val snap = db.collection(company).document(deviceId).get().await()
                            if (snap.exists()) {
                                val lastUpdatedBy = snap.getString("last_updated_by") ?: ""
                                val remoteTimestamp = snap.getLong("last_updated_at") ?: 0L

                                if (lastUpdatedBy == "admin" && remoteTimestamp > lastSyncTime && !hasOfflineChanges) {
                                    // ALLINEA DA ADMIN: Sovrascrivi l'Excel locale con i dati provenienti da Firestore
                                    Log.i(TAG, "Rilevata modifica da Admin per $company. Sovrascrivo l'Excel locale.")
                                    val materialsMap = snap.get("materiali") as? Map<*, *>
                                    val remoteDataList = materialsMap?.mapNotNull { (key, value) ->
                                        if (key is String && value is String) ExcelRowData(key, value) else null
                                    } ?: emptyList()
                                    
                                    // Salva il file Excel locale
                                    excelRepo.saveExcelFile(uri, remoteDataList).onSuccess {
                                        syncPrefs.edit().putLong("last_sync_timestamp_$company", remoteTimestamp).apply()
                                        Log.i(TAG, "Excel locale per $company allineato con successo alle modifiche Admin.")
                                        
                                        // Segnala a MainActivity di ricaricare i dati a schermo
                                        syncPrefs.edit().putLong("lists_updated_at", System.currentTimeMillis()).apply()
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            (MyApplication.getCurrentActivity() as? MainActivity)?.onListsUpdatedFromDashboard()
                                        }
                                    }
                                    return@let
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Errore durante il controllo delle modifiche Admin per $company: ${e.message}", e)
                        }

                        // Sync standard in upload
                        excelRepo.readExcelFile(uri, company).onSuccess { data ->
                            val isEmpty = data.all { it.value.isEmpty() || it.value == "0" }
                            if (!isEmpty) {
                                firebaseRepo.syncToFirestore(context, company, techName, data, lat, lng, deviceId = deviceId)
                            }
                        }
                    }
                }
            }

            // Consumo
            settingsRepo.consumoFileUri?.let { uriString ->
                val uri = Uri.parse(uriString)
                if (fileStorageManager.isUriAccessible(uri)) {
                    val localFile = try {
                        val docFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                        if (docFile != null && docFile.exists()) {
                            docFile.lastModified()
                        } else 0L
                    } catch (e: Exception) { 0L }

                    val lastSyncTime = syncPrefs.getLong("last_sync_timestamp_Consumo", 0L)
                    val hasOfflineChanges = localFile > (lastSyncTime + 2000L)

                    // Controllo modifiche Admin per Consumo
                    try {
                        val db = com.google.firebase.ktx.Firebase.firestore
                        val snap = db.collection("Consumo").document(deviceId).get().await()
                        if (snap.exists()) {
                            val lastUpdatedBy = snap.getString("last_updated_by") ?: ""
                            val remoteTimestamp = snap.getLong("last_updated_at") ?: 0L

                            if (lastUpdatedBy == "admin" && remoteTimestamp > lastSyncTime && !hasOfflineChanges) {
                                Log.i(TAG, "Rilevata modifica da Admin per Consumo. Sovrascrivo l'Excel locale.")
                                val materialsMap = snap.get("materiali") as? Map<*, *>
                                val remoteDataList = materialsMap?.mapNotNull { (key, value) ->
                                    if (key is String && value is String) ExcelRowData(key, value) else null
                                } ?: emptyList()
                                
                                ConsumoRepository(context).saveConsumoFile(uri, remoteDataList, techName).onSuccess {
                                    syncPrefs.edit().putLong("last_sync_timestamp_Consumo", remoteTimestamp).apply()
                                    Log.i(TAG, "Excel locale per Consumo allineato con successo alle modifiche Admin.")
                                    
                                    syncPrefs.edit().putLong("lists_updated_at", System.currentTimeMillis()).apply()
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        (MyApplication.getCurrentActivity() as? MainActivity)?.onListsUpdatedFromDashboard()
                                    }
                                }
                                return@let
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Errore controllo modifiche Admin per Consumo: ${e.message}", e)
                    }

                    ConsumoRepository(context).readConsumoFile(uri).onSuccess { data ->
                        val isEmpty = data.all { it.value.isEmpty() || it.value == "0" }
                        if (!isEmpty) {
                            firebaseRepo.syncToFirestore(context, "Consumo", techName, data, lat, lng, deviceId = deviceId)
                        }
                    }
                }
            }
            
            // 4. PFS Areas: sincronizzate SOLO da FavoriteManager.persistFavoritesToFirebase()
            // quando l'utente modifica i preferiti, non ad ogni sync periodico.
            // Questo evita ~1 write/sync su devices_names che triggerava il listener della dashboard.
        }
    }

}
