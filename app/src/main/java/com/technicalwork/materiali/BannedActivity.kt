package com.technicalwork.materiali

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class BannedActivity : AppCompatActivity() {
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
    }
}

