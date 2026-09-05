package com.technicalwork.materiali

import android.util.Log
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Gestore centralizzato dell'autenticazione Firebase per l'app Android.
 *
 * Utilizza l'autenticazione anonima (signInAnonymously) per garantire la blindatura
 * di Cloud Firestore (regole Versione B con controllo `request.auth != null`),
 * senza richiedere alcuna interazione o credenziale all'utente (tecnico sul campo).
 */
object AuthManager {

    private const val TAG = "TW_AuthManager"
    private val mutex = Mutex()

    /**
     * Verifica sincrona dello stato di autenticazione.
     * Restituisce true se l'utente possiede già una sessione attiva (anche anonima).
     */
    fun isAuthenticated(): Boolean {
        return Firebase.auth.currentUser != null
    }

    /**
     * Restituisce il FirebaseUser associato alla sessione corrente, oppure null se non autenticato.
     */
    fun getCurrentUser(): FirebaseUser? {
        return Firebase.auth.currentUser
    }

    /**
     * Garantisce che l'applicazione sia autenticata con Firebase.
     *
     * - Se l'utente è già loggato (sessione locale persistita), ritorna immediatamente true.
     * - Se currentUser è null, tenta l'accesso anonimo tramite Firebase.auth.signInAnonymously().
     * - L'accesso è protetto da Mutex per evitare chiamate concorrenti e duplicazione di token.
     * - Se il dispositivo è offline, intercetta l'eccezione senza crash e restituisce false,
     *   permettendo il normale lavoro offline tramite la cache locale di Firestore.
     */
    suspend fun ensureAuthenticated(): Boolean = withContext(Dispatchers.IO) {
        // Fast path: sessione già esistente e persistita
        if (isAuthenticated()) {
            return@withContext true
        }

        mutex.withLock {
            // Double check dopo l'acquisizione del lock
            if (isAuthenticated()) {
                return@withContext true
            }

            try {
                Log.d(TAG, "Avvio autenticazione anonima Firebase in background...")
                val result = Firebase.auth.signInAnonymously().await()
                val user = result.user
                Log.i(TAG, "Autenticazione anonima Firebase completata con successo! UID: ${user?.uid}")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Autenticazione anonima Firebase non riuscita (dispositivo offline?): ${e.message}")
                false
            }
        }
    }
}
