package com.technicalwork.materiali

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BannedActivity : AppCompatActivity() {

    private var listenerRegistration: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_banned)
        supportActionBar?.hide()
        
        // Blocca la chiusura dell'activity col tasto back
        // Su Android 13+ si usa OnBackPressedDispatcher
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Non fare nulla, l'utente e' bloccato qui
            }
        })

        // Ascolta in tempo reale se l'amministratore sblocca o cancella il dispositivo
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        lifecycleScope.launch(Dispatchers.IO) {
            AuthManager.ensureAuthenticated()
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                listenerRegistration = Firebase.firestore
                    .collection("settings")
                    .document("devices_names")
                    .addSnapshotListener { snapshot, e ->
                        if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                        
                        val raw = snapshot.data?.get(deviceId)
                        val isBanned = (raw as? Map<*, *>)?.get("banned") as? Boolean ?: false
                        
                        // Se non è bannato o se il dispositivo è stato cancellato dal db (raw == null)
                        if (raw == null || !isBanned) {
                            val prefs = getSharedPreferences("GeoNavPrefs", Context.MODE_PRIVATE)
                            prefs.edit().putBoolean("is_banned", false).apply()
                            
                            val intent = Intent(this@BannedActivity, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                            startActivity(intent)
                            finish()
                        }
                    }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listenerRegistration?.remove()
    }
}

