package com.technicalwork.materiali

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.coroutineScope

import androidx.work.Data

/**
 * Worker che esegue il sync Firebase completo in background.
 *
 * Lanciato via WorkManager al click del tasto Salva, garantisce che
 * la dashboard venga aggiornata anche se l'utente esce dall'app
 * subito dopo il salvataggio.
 *
 * Usa ExistingWorkPolicy.REPLACE: se l'utente salva più volte
 * in rapida successione, solo l'ultimo sync viene eseguito.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val latRaw = inputData.getDouble("lat", Double.NaN)
            val lngRaw = inputData.getDouble("lng", Double.NaN)
            val isFullSync = inputData.getBoolean("isFullSync", true)
            val lat = if (latRaw.isNaN()) null else latRaw
            val lng = if (lngRaw.isNaN()) null else lngRaw

            // coroutineScope crea un CoroutineScope dal contesto suspend corrente,
            // che è ciò che performFullSync si aspetta come parametro.
            coroutineScope {
                SyncManager(applicationContext).performFullSync(this, lat, lng, isFullSync)
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            // Riprova una volta in caso di errore temporaneo (es. rete instabile)
            if (runAttemptCount < 1) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "firebase_full_sync"

        /**
         * Accoda un sync Firebase. Se ne è già in coda uno, lo sostituisce
         * così non si accumulano richieste ridondanti.
         */
        fun enqueue(context: Context, isFullSync: Boolean = true) {
            try {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val fusedClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
                    fusedClient.lastLocation.addOnCompleteListener { task ->
                        var lat: Double? = null
                        var lng: Double? = null
                        if (task.isSuccessful && task.result != null) {
                            lat = task.result.latitude
                            lng = task.result.longitude
                        } else {
                            val (fbLat, fbLng) = SyncManager.getLastLocationHelper(context)
                            lat = fbLat
                            lng = fbLng
                        }
                        enqueueWithLocation(context, lat, lng, isFullSync)
                    }
                } else {
                    enqueueWithLocation(context, null, null, isFullSync)
                }
            } catch (e: Exception) {
                enqueueWithLocation(context, null, null, isFullSync)
            }
        }

        private fun enqueueWithLocation(context: Context, lat: Double?, lng: Double?, isFullSync: Boolean) {
            val dataBuilder = Data.Builder()
            if (lat != null && lng != null) {
                dataBuilder.putDouble("lat", lat)
                dataBuilder.putDouble("lng", lng)
            }
            dataBuilder.putBoolean("isFullSync", isFullSync)

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(dataBuilder.build())
                    .build()
            )
        }
    }
}
