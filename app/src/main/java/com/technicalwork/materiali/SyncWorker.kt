package com.technicalwork.materiali

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.coroutineScope

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
            // coroutineScope crea un CoroutineScope dal contesto suspend corrente,
            // che è ciò che performFullSync si aspetta come parametro.
            coroutineScope {
                SyncManager(applicationContext).performFullSync(this)
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
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().build()
            )
        }
    }
}
