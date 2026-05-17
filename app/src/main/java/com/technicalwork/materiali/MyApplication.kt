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
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyApplication : Application(), Application.ActivityLifecycleCallbacks {

    private var currentActivity: Activity? = null
    private val appScope = CoroutineScope(Dispatchers.Default)
    private val updateCheckHandler = Handler(Looper.getMainLooper())

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
        registerActivityLifecycleCallbacks(this)
        
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Aggiornamento GPS e presenza
                SyncWorker.enqueue(this@MyApplication, isFullSync = false)
                
                // Controllo aggiornamenti globale
                checkUpdatesGlobally()
            }
        })
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
}
