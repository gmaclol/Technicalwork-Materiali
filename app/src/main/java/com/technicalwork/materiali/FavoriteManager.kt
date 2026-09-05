package com.technicalwork.materiali

import android.content.Context
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Helper centralizzato per:
 * 1. Lettura/scrittura dei preferiti PFS in SharedPreferences ("GeoNavPrefs")
 * 2. Registrazione del listener real-time Firebase devices_names per ricevere
 *    aggiornamenti remoti di nome tecnico e aree preferite dalla Dashboard.
 *
 * Usare questa classe al posto della logica inline duplicata in
 * MainActivity, PfsActivity e GeoNavActivity.
 */
object FavoriteManager {

    private const val PREFS_NAME = "GeoNavPrefs"
    private const val FAV_PREFIX = "fav_"

    // ── Lettura preferiti ──────────────────────────────────────────────────

    /** Restituisce la lista dei nomi salvati come preferiti. */
    fun getFavorites(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.all
            .filter { it.key.startsWith(FAV_PREFIX) && it.value == true }
            .map { it.key.removePrefix(FAV_PREFIX) }
    }

    /** Restituisce true se [name] è nei preferiti. */
    fun isFavorite(context: Context, name: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("$FAV_PREFIX$name", false)
    }

    // ── Scrittura preferiti ────────────────────────────────────────────────

    /** Imposta o rimuove [name] dai preferiti. */
    fun setFavorite(context: Context, name: String, isFav: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (isFav) {
            prefs.edit().putBoolean("$FAV_PREFIX$name", true).apply()
        } else {
            prefs.edit().remove("$FAV_PREFIX$name").apply()
        }
    }

    /**
     * Sostituisce tutti i preferiti con [newFavorites].
     * Usato quando arriva una lista remota dalla Dashboard.
     */
    fun replaceFavorites(context: Context, newFavorites: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(FAV_PREFIX) }.forEach { editor.remove(it) }
        newFavorites.forEach { editor.putBoolean("$FAV_PREFIX$it", true) }
        editor.apply()
    }

    // ── Abilitazione PFS da Dashboard ──────────────────────────────────────

    /** Restituisce true se l'accesso alla sezione PFS è abilitato dall'amministratore (default: false). */
    fun isPfsEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("is_pfs_enabled", false)
    }

    /** Salva localmente lo stato di abilitazione PFS. */
    fun setPfsEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_pfs_enabled", enabled).apply()
    }

    // ── Firebase: sincronizzazione remota ─────────────────────────────────

    /**
     * Registra un SnapshotListener su settings/devices_names.
     * Quando arrivano aggiornamenti remoti:
     *  - aggiorna lo stato di abilitazione PFS (pfs_enabled, default false)
     *  - aggiorna il killswitch ban
     *  - aggiorna il nome tecnico e le aree preferite PFS (se timestamp più recente)
     * e invoca [onUpdate] con (nuovoNome?, nuoviPreferiti?, isPfsEnabled) per aggiornare la UI.
     *
     * Ricordati di chiamare .remove() sul [ListenerRegistration] restituito
     * in onDestroy() dell'Activity.
     *
     * @param context   Context dell'Activity (per prefs e contentResolver)
     * @param settingsRepo  SettingsRepository per leggere/scrivere lastNameUpdateTimestamp
     * @param onUpdate  Callback eseguita sul Main thread con (nuovoNome?, nuoviPreferiti?, isPfsEnabled)
     */
    fun attachDashboardListener(
        context: Context,
        settingsRepo: SettingsRepository,
        onUpdate: (newName: String?, newFavorites: List<String>?, isPfsEnabled: Boolean) -> Unit
    ): ListenerRegistration {
        val deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        return Firebase.firestore
            .collection("settings")
            .document("devices_names")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

                val raw = snapshot.get(deviceId) ?: return@addSnapshotListener
                val localTimestamp = settingsRepo.lastNameUpdateTimestamp
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                var remoteName: String? = null
                var remoteTimestamp = 0L
                var remotePfsAreas: List<String>? = null
                var newPfsEnabled = prefs.getBoolean("is_pfs_enabled", false)

                when (raw) {
                    is String -> {
                        remoteName = raw
                        remoteTimestamp = 0L
                    }
                    is Map<*, *> -> {
                        remoteName = raw["name"] as? String
                        remoteTimestamp = (raw["updatedAt"] as? Number)?.toLong() ?: 0L
                        @Suppress("UNCHECKED_CAST")
                        remotePfsAreas = raw["pfsAreas"] as? List<String>
                        
                        // Check per Killswitch
                        val isBanned = raw["banned"] as? Boolean ?: false
                        prefs.edit().putBoolean("is_banned", isBanned).apply()
                        
                        if (isBanned) {
                            val intent = android.content.Intent(context, BannedActivity::class.java).apply {
                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                            context.startActivity(intent)
                            return@addSnapshotListener
                        }

                        // Check abilitazione PFS da Dashboard (default false se assente)
                        newPfsEnabled = (raw["pfs_enabled"] as? Boolean)
                            ?: (raw["pfsEnabled"] as? Boolean)
                            ?: false
                    }
                }

                val prevPfsEnabled = prefs.getBoolean("is_pfs_enabled", false)
                if (newPfsEnabled != prevPfsEnabled) {
                    prefs.edit().putBoolean("is_pfs_enabled", newPfsEnabled).apply()
                }

                if (remoteTimestamp > localTimestamp) {
                    // Applica preferiti localmente
                    if (remotePfsAreas != null) {
                        replaceFavorites(context, remotePfsAreas)
                    }
                    settingsRepo.lastNameUpdateTimestamp = remoteTimestamp
                    onUpdate(remoteName, remotePfsAreas, newPfsEnabled)
                } else {
                    // Notifica comunque lo stato PFS attuale alla UI
                    onUpdate(null, null, newPfsEnabled)
                }
            }
    }

    // ── Firebase: salvataggio preferiti ───────────────────────────────────

    /**
     * Salva la lista corrente dei preferiti su Firestore e aggiorna il
     * timestamp locale in modo che il listener remoto non la ri-applichi.
     *
     * Eseguire in un Dispatchers.IO scope.
     */
    fun persistFavoritesToFirebase(
        context: Context,
        settingsRepo: SettingsRepository,
        scope: CoroutineScope
    ) {
        val deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        val allFavs = getFavorites(context)
        val now = System.currentTimeMillis()

        scope.launch(Dispatchers.IO) {
            FirebaseRepository().updatePfsAreas(deviceId, allFavs, settingsRepo.technicianName)
            settingsRepo.lastNameUpdateTimestamp = now
            // NB: NON triggerare performFullSync qui.
            // L'unico scopo e' salvare i preferiti su Firestore.
            // Un fullSync riscriverebbe devices_names causando loop col listener.
        }
    }

    // ── Inizializzazione preferiti di default ─────────────────────────────

    /**
     * Imposta i preferiti predefiniti se è il primo avvio (flag "favorites_initialized").
     * Chiamare in MainActivity.onCreate prima del listener Firebase.
     */
    fun initializeDefaultFavoritesIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("favorites_initialized", false)) {
            prefs.edit()
                .putBoolean("${FAV_PREFIX}TOH_1", true)
                .putBoolean("${FAV_PREFIX}Asti", true)
                .putBoolean("${FAV_PREFIX}Biella", true)
                .putBoolean("favorites_initialized", true)
                .apply()
        }
    }
}
