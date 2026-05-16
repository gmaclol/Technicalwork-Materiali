package com.technicalwork.materiali

import android.content.Context
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gestore centralizzato per la sincronizzazione totale dell'app.
 * Scarica le liste aggiornate, rileva il GPS e invia lo stato di tutti i file a Firestore.
 */
class SyncManager(private val context: Context) {

    companion object {
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

    fun performFullSync(scope: CoroutineScope, passedLat: Double? = null, passedLng: Double? = null) {
        scope.launch(Dispatchers.IO) {
            val settingsRepo = SettingsRepository(context)
            val configManager = ConfigManager(context)
            val techName = settingsRepo.technicianName ?: return@launch
            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            
            // 1. Fetch Configurazione Remota e Liste (GitHub)
            configManager.fetchRemoteConfig()
            configManager.fetchRemoteRegionsJson()
            ListUpdater().syncLists(context, configManager.getCompanies(), configManager.getPfsAreas())

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
                } catch (_: Exception) {
                    // Fallback finale a LocationManager raw
                    val (fbLat, fbLng) = getLastLocationHelper(context)
                    lat = fbLat
                    lng = fbLng
                }
            }

            // 3. Sync di tutti i file Excel (Aziende e Consumo)
            val firebaseRepo = FirebaseRepository()
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
                        excelRepo.readExcelFile(uri, company).onSuccess { data ->
                            firebaseRepo.syncToFirestore(context, company, techName, data, lat, lng, deviceId = deviceId)
                        }
                    }
                }
            }

            // Consumo
            settingsRepo.consumoFileUri?.let { uriString ->
                val uri = Uri.parse(uriString)
                if (fileStorageManager.isUriAccessible(uri)) {
                    ConsumoRepository(context).readConsumoFile(uri).onSuccess { data ->
                        firebaseRepo.syncToFirestore(context, "Consumo", techName, data, lat, lng, deviceId = deviceId)
                    }
                }
            }
            
            // 4. PFS Areas: sincronizzate SOLO da FavoriteManager.persistFavoritesToFirebase()
            // quando l'utente modifica i preferiti, non ad ogni sync periodico.
            // Questo evita ~1 write/sync su devices_names che triggerava il listener della dashboard.
        }
    }

}
