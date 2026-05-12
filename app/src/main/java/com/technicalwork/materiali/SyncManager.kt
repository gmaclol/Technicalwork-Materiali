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

    fun performFullSync(scope: CoroutineScope) {
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
            val position = withContext(Dispatchers.Main) { getLastLocationHelper(context) }
            val lat = position.first
            val lng = position.second

            // 3. Sync di tutti i file Excel (Aziende e Consumo)
            val firebaseRepo = FirebaseRepository()
            val excelRepo = ExcelRepository(context)
            val fileStorageManager = FileStorageManager(context)
            
            // Invia subito l'aggiornamento versione app in caso di riavvio post-update
            firebaseRepo.updateAppVersionOnly(deviceId, configManager.getCompanies())

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
            
            // 4. Sync Aree PFS (Preferiti)
            val allFavs = FavoriteManager.getFavorites(context)
            firebaseRepo.updatePfsAreas(deviceId, allFavs)
        }
    }

    private fun getLastLocationHelper(context: Context): Pair<Double?, Double?> {
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
