package com.technicalwork.materiali

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.os.BatteryManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings

class MyApplication : Application(), Application.ActivityLifecycleCallbacks {

    companion object {
        private var instance: MyApplication? = null
        
        fun getCurrentActivity(): Activity? {
            return instance?.currentActivity
        }
    }

    private var currentActivity: Activity? = null
    private val appScope = CoroutineScope(Dispatchers.Default)
    private val updateCheckHandler = Handler(Looper.getMainLooper())
    private var dashboardListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null

    private val updateCheckRunnable = object : Runnable {
        override fun run() {
            currentActivity?.let { activity ->
                checkGlobalPendingUpdate(activity)
            }
            val prefs = getSharedPreferences("updates", Context.MODE_PRIVATE)
            if (prefs.contains("pending_download_id")) {
                updateCheckHandler.postDelayed(this, 2000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(this)
        
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Avvia il listener Firestore in tempo reale per Forza Aggiornamento
                startDashboardListener()

                // Aggiornamento GPS e presenza
                SyncWorker.enqueue(this@MyApplication, isFullSync = false)
                
                // Controllo aggiornamenti globale
                checkUpdatesGlobally()

                // Aggiornamento telemetria dispositivo se cambiata
                syncDeviceTelemetryIfNeeded()
            }

            override fun onStop(owner: LifecycleOwner) {
                // Ferma il listener in tempo reale quando l'app va in background per risparmiare risorse
                stopDashboardListener()
            }
        })
    }

    private fun syncDeviceTelemetryIfNeeded() {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: return
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val appVersion = BuildConfig.VERSION_NAME
        
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val gpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false

        // Confronto cache
        val prefs = getSharedPreferences("device_telemetry_cache", Context.MODE_PRIVATE)
        val cachedModel = prefs.getString("device_model", "")
        val cachedVersion = prefs.getString("app_version", "")
        val cachedBattery = prefs.getInt("battery_level", -2)
        val cachedGps = prefs.getBoolean("gps_enabled", !gpsEnabled) // forziamo differenza se non salvato

        val isChanged = deviceModel != cachedModel ||
                appVersion != cachedVersion ||
                batteryLevel != cachedBattery ||
                gpsEnabled != cachedGps

        if (isChanged) {
            appScope.launch(Dispatchers.IO) {
                val configManager = ConfigManager(this@MyApplication)
                val companies = configManager.getCompanies()
                
                FirebaseRepository().updateDeviceTelemetry(
                    deviceId = deviceId,
                    companies = companies,
                    deviceModel = deviceModel,
                    appVersion = appVersion,
                    batteryLevel = batteryLevel,
                    gpsEnabled = gpsEnabled
                )

                // Salva lo stato inviato in cache locale
                prefs.edit().apply {
                    putString("device_model", deviceModel)
                    putString("app_version", appVersion)
                    putInt("battery_level", batteryLevel)
                    putBoolean("gps_enabled", gpsEnabled)
                }.apply()
            }
        }
    }


    private fun checkUpdatesGlobally() {
        val updateManager = UpdateManager(this)
        appScope.launch {
            updateManager.checkForUpdates { versionName, downloadUrl ->
                Handler(Looper.getMainLooper()).post {
                    currentActivity?.let { activity ->
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            AlertDialog.Builder(activity)
                                .setTitle(getString(R.string.dialog_title_update_available))
                                .setMessage(getString(R.string.dialog_msg_update_available, versionName))
                                .setPositiveButton(getString(R.string.btn_update)) { _, _ ->
                                    updateManager.downloadAndInstall(downloadUrl) {
                                        updateCheckHandler.postDelayed(updateCheckRunnable, 2000)
                                    }
                                }
                                .setNegativeButton(getString(R.string.btn_after), null)
                                .show()
                        }
                    }
                }
            }
        }
    }

    private fun checkGlobalPendingUpdate(activity: Activity) {
        val prefs = getSharedPreferences("updates", Context.MODE_PRIVATE)
        val downloadId = prefs.getLong("pending_download_id", -1L)
        val apkPath = prefs.getString("pending_apk_path", null)

        if (downloadId != -1L && apkPath != null) {
            val query = android.app.DownloadManager.Query().setFilterById(downloadId)
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val cursor = manager.query(query)

            if (cursor != null && cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS)
                if (statusIndex >= 0) {
                    val status = cursor.getInt(statusIndex)
                    if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                        prefs.edit().remove("pending_download_id").apply()
                        val file = java.io.File(apkPath)
                        if (file.exists()) {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                AlertDialog.Builder(activity)
                                    .setTitle("Aggiornamento scaricato")
                                    .setMessage("Il nuovo aggiornamento è pronto. Installare ora?")
                                    .setPositiveButton("Installa") { _, _ ->
                                        UpdateManager(this).installApk(file)
                                    }
                                    .setNegativeButton("Dopo", null)
                                    .show()
                            }
                        }
                    }
                }
            }
            cursor?.close()
        }
    }

    override fun onActivityResumed(activity: Activity) { 
        currentActivity = activity 
        
        // Al resume, controlliamo se c'è un download pendente
        val prefs = getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (prefs.contains("pending_download_id")) {
            updateCheckHandler.postDelayed(updateCheckRunnable, 2000)
        }
    }
    
    override fun onActivityPaused(activity: Activity) { 
        if (currentActivity == activity) {
            currentActivity = null 
        }
        updateCheckHandler.removeCallbacks(updateCheckRunnable)
    }
    
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    private val adminListeners = mutableMapOf<String, com.google.firebase.firestore.ListenerRegistration>()

    private fun startDashboardListener() {
        if (dashboardListenerRegistration != null) return
        
        try {
            val db = Firebase.firestore
            dashboardListenerRegistration = db.collection("settings").document("dashboard")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e("TW_MyApplication", "Errore nel listener dashboard in tempo reale: ${error.message}", error)
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val remoteTimestamp = snapshot.getLong("forceListUpdate") ?: 0L
                        val syncPrefs = getSharedPreferences("sync_meta", Context.MODE_PRIVATE)
                        val localTimestamp = syncPrefs.getLong("last_force_list_update", 0L)
                        
                        if (remoteTimestamp > localTimestamp) {
                            Log.i("TW_MyApplication", "Rilevato 'Forza Aggiornamento' in TEMPO REALE (remoto=$remoteTimestamp > locale=$localTimestamp). Avvio fetch GitHub!")
                            
                            appScope.launch(Dispatchers.IO) {
                                try {
                                    val configManager = ConfigManager(this@MyApplication)
                                    configManager.fetchRemoteConfig()
                                    configManager.fetchRemoteRegionsJson()
                                    ListUpdater().syncLists(this@MyApplication, configManager.getCompanies(), configManager.getPfsAreas())
                                    
                                    syncPrefs.edit().putLong("last_force_list_update", remoteTimestamp).apply()
                                    Log.i("TW_MyApplication", "Fetch in tempo reale completato con successo. SharedPreferences allineate.")
                                    
                                    // Segna che le liste sono state aggiornate, così MainActivity può rivalidare
                                    syncPrefs.edit().putLong("lists_updated_at", System.currentTimeMillis()).apply()
                                    
                                    // Notifica direttamente la MainActivity corrente se attiva
                                    Handler(Looper.getMainLooper()).post {
                                        (currentActivity as? MainActivity)?.onListsUpdatedFromDashboard()
                                    }
                                } catch (e: Exception) {
                                    Log.e("TW_MyApplication", "Errore nel fetch in tempo reale da GitHub: ${e.message}", e)
                                }
                            }
                        }
                    }
                }
            Log.d("TW_MyApplication", "Dashboard realtime listener registrato con successo")

            // Registra listener per rilevare modifiche dell'admin in tempo reale
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val configManager = ConfigManager(this)
            val allCompanies = configManager.getCompanies().toMutableList()
            if (!allCompanies.contains("Consumo")) allCompanies.add("Consumo")

            allCompanies.forEach { company ->
                if (!adminListeners.containsKey(company)) {
                    val reg = db.collection(company).document(deviceId)
                        .addSnapshotListener { snap, err ->
                            if (err != null) return@addSnapshotListener
                            if (snap != null && snap.exists()) {
                                val lastUpdatedBy = snap.getString("last_updated_by")
                                if (lastUpdatedBy == "admin") {
                                    Log.i("TW_MyApplication", "Rilevata modifica da Admin per $company in tempo reale! Avvio Sync di allineamento.")
                                    // Eseguiamo il Sync Manager per elaborare l'allineamento del file Excel
                                    SyncManager(this@MyApplication).performFullSync(appScope, isFullSync = true)
                                }
                            }
                        }
                    adminListeners[company] = reg
                }
            }
        } catch (e: Exception) {
            Log.e("TW_MyApplication", "Impossibile registrare il dashboard listener: ${e.message}", e)
        }
    }

    private fun stopDashboardListener() {
        dashboardListenerRegistration?.remove()
        dashboardListenerRegistration = null
        adminListeners.values.forEach { it.remove() }
        adminListeners.clear()
        Log.d("TW_MyApplication", "Dashboard realtime listener rimosso")
    }
}
