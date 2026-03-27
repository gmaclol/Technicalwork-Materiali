package com.technicalwork.materiali

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationView
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PfsActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rvPfs: RecyclerView
    private lateinit var pbPfs: ProgressBar
    private lateinit var adapter: PfsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pfs)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        drawerLayout = findViewById(R.id.drawerLayout)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val navigationView = findViewById<NavigationView>(R.id.navigationView)

        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(0, systemBars.top, 0, 0)
            navigationView.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        setSupportActionBar(toolbar)
        toolbar.setTitleTextAppearance(this, androidx.appcompat.R.style.TextAppearance_AppCompat_Widget_ActionBar_Title)

        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        val headerView = navigationView.getHeaderView(0)
        val appLogo = headerView.findViewById<ImageView>(R.id.app_logo)
        val pfsLogo = headerView.findViewById<ImageView>(R.id.pfs_logo)

        pfsLogo?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        appLogo?.setOnClickListener {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("last_activity", "MainActivity").apply()
            
            val resetIntent = Intent(this, MainActivity::class.java)
            resetIntent.putExtra("skip_routing", true)
            startActivity(resetIntent)
            finish()
        }

        rvPfs = findViewById(R.id.rvPfs)
        pbPfs = findViewById(R.id.pbPfs)

        adapter = PfsAdapter(
            emptyList(),
            onSubmitAddress = { item, newAddress -> submitMissingAddress(item, newAddress) },
            onPfsClick = { item -> logPfsClick(item) }
        )
        rvPfs.layoutManager = LinearLayoutManager(this)
        rvPfs.adapter = adapter

        val btnToh1 = findViewById<MaterialButton>(R.id.navBtnToh1)
        val btnAsti = findViewById<MaterialButton>(R.id.navBtnAsti)
        val pfsPrefs = getSharedPreferences("pfs_prefs", Context.MODE_PRIVATE)

        val initialArea = pfsPrefs.getString("pfs_last_area", "TOH1") ?: "TOH1"
        loadArea(initialArea)

        btnToh1?.setOnClickListener { loadArea("TOH1") }
        btnAsti?.setOnClickListener { loadArea("Asti") }
    }

    private fun loadArea(area: String) {
        val pfsPrefs = getSharedPreferences("pfs_prefs", Context.MODE_PRIVATE)
        pfsPrefs.edit().putString("pfs_last_area", area).apply()
        supportActionBar?.title = "PFS - $area"
        
        // Non forzare la chiusura se è già chiuso per prevenire comportamenti anomali al primo avvio
        if(drawerLayout.isDrawerOpen(GravityCompat.START)) {
             drawerLayout.closeDrawer(GravityCompat.START)
        }
        
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { 
                pbPfs.visibility = View.VISIBLE 
                rvPfs.visibility = View.GONE
            }
            try {
                val url = URL("https://raw.githubusercontent.com/gmaclol/Technicalwork-Materiali/master/lists/$area.txt")
                val rawText = url.readText()
                val parsedItems = rawText.lines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        if (line.contains("::::")) {
                            val parts = line.split("::::")
                            if (parts.size >= 2) PfsItem(parts[0].trim(), parts[1].trim(), true) else null
                        } else if (line.contains("::")) {
                            val parts = line.split("::")
                            if (parts.size >= 2) PfsItem(parts[0].trim(), parts[1].trim(), false) else null
                        } else null
                    }
                val (lat, lng) = getLastLocation()
                withContext(Dispatchers.Main) {
                    adapter.updateData(parsedItems, lat, lng)
                    pbPfs.visibility = View.GONE
                    rvPfs.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pbPfs.visibility = View.GONE
                    Toast.makeText(this@PfsActivity, "Errore download lista", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun logPfsClick(item: PfsItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            val (lat, lng) = getLastLocation()
            if (lat == null || lng == null) return@launch // Don't log if location unknown
            
            val settingsRepo = SettingsRepository(this@PfsActivity)
            val techName = settingsRepo.technicianName ?: "Sconosciuto"
            val timestamp = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(Date())
            
            val data = hashMapOf<String, Any>(
                "nome_pfs" to item.name,
                "indirizzo_pfs" to item.address,
                "tecnico" to techName,
                "orario" to timestamp,
                "lat" to lat,
                "lng" to lng
            )
            
            try {
                Firebase.firestore
                    .collection("pfs_logs")
                    .add(data)
            } catch (e: Exception) {
                // Silenzioso, non disturbiamo l'utente per un log fallito
            }
        }
    }

    private fun submitMissingAddress(item: PfsItem, newAddress: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val (lat, lng) = getLastLocation()
            val settingsRepo = SettingsRepository(this@PfsActivity)
            val techName = settingsRepo.technicianName ?: "Sconosciuto"
            val timestamp = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date())
            
            val data = hashMapOf<String, Any>(
                "nome_pfs" to item.name,
                "nuovo_indirizzo" to newAddress,
                "tecnico" to techName,
                "orario" to timestamp
            )
            lat?.let { data["lat"] = it }
            lng?.let { data["lng"] = it }
            
            try {
                Firebase.firestore
                    .collection("pfs_segnalati")
                    .add(data)
                    .await()
                    
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PfsActivity, "Indirizzo inviato con successo!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PfsActivity, "Errore di invio a Firebase", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getLastLocation(): Pair<Double?, Double?> {
        return try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return null to null
            }
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) 
                      ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            loc?.latitude to loc?.longitude
        } catch (e: Exception) {
            null to null
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.pfs_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> {
                Toast.makeText(this, "Ricerca in sviluppo...", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
