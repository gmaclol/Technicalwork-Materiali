package com.technicalwork.materiali

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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
import com.google.firebase.firestore.ListenerRegistration

class PfsActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rvPfs: RecyclerView
    private lateinit var pbPfs: ProgressBar
    private lateinit var adapter: PfsAdapter
    private lateinit var tvTechName: TextView
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var tvCustomTitle: TextView
    private lateinit var configManager: ConfigManager
    private var dashboardListener: ListenerRegistration? = null
    // Traccia il comune/area corrente per includerlo automaticamente nelle segnalazioni
    private var currentArea: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pfs)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        settingsRepository = SettingsRepository(this)
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
        supportActionBar?.setDisplayShowTitleEnabled(false)
        tvCustomTitle = findViewById(R.id.customToolbarTitle)
        tvCustomTitle.isSelected = true

        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        configManager = ConfigManager(this)
        setupDynamicDrawer()

        val headerView = navigationView.getHeaderView(0)
        val appLogo = headerView.findViewById<ImageView>(R.id.app_logo)
        val pfsLogo = headerView.findViewById<ImageView>(R.id.pfs_logo)

        pfsLogo?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        appLogo?.setOnClickListener {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit { putString("last_activity", "MainActivity") }
            val resetIntent = Intent(this, MainActivity::class.java)
            resetIntent.putExtra("skip_routing", true)
            startActivity(resetIntent)
            finish()
        }

        // --- Configurazione Sezione Tecnico nel Drawer ---
        tvTechName = findViewById(R.id.tvTechName)
        val layoutTechName = findViewById<View>(R.id.layoutTechName)
        
        val currentName = settingsRepository.technicianName
        tvTechName.text = currentName ?: "Non impostato"
        
        layoutTechName?.setOnClickListener {
            showTechnicianNameDialog()
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

        val pfsPrefs = getSharedPreferences("pfs_prefs", Context.MODE_PRIVATE)

        val areas = configManager.getPfsAreas()
        val defaultArea = if (areas.isNotEmpty()) areas[0] else "TOH1"
        var initialArea = pfsPrefs.getString("pfs_last_area", defaultArea) ?: defaultArea
        
        val selectedAreaExtra = intent.getStringExtra("SELECTED_AREA")
        if (selectedAreaExtra != null) {
            initialArea = selectedAreaExtra
        }
        
        loadArea(initialArea)
        
        // I bottoni delle aree vengono ora generati dinamicamente in setupDynamicDrawer()

        findViewById<MaterialButton>(R.id.btnGeoNav)?.setOnClickListener {
            val intent = Intent(this, GeoNavActivity::class.java)
            startActivity(intent)
        }

        setupDashboardListener()
    }

    private fun setupDashboardListener() {
        lifecycleScope.launch(Dispatchers.IO) {
            AuthManager.ensureAuthenticated()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    dashboardListener = FavoriteManager.attachDashboardListener(
                        context = this@PfsActivity,
                        settingsRepo = settingsRepository
                    ) { _, newFavorites ->
                        if (newFavorites != null) {
                            runOnUiThread { setupDynamicDrawer() }
                        }
                    }
                }
            }
        }
    }

    private fun showTechnicianNameDialog() {
        val input = EditText(this)
        val currentName = settingsRepository.technicianName
        if (currentName != null) {
            input.setText(currentName)
            input.setSelectAllOnFocus(true)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_edit_tech_name))
            .setMessage(getString(R.string.dialog_msg_enter_tech_name))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    settingsRepository.technicianName = newName
                    tvTechName.text = newName
                    
                    // Sincronizza immediatamente il nuovo nome su Firebase
                    @SuppressLint("HardwareIds")
                    val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
                    lifecycleScope.launch(Dispatchers.IO) {
                        FirebaseRepository().updateTechnicianName(deviceId, newName, configManager.getCompanies())
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }


    private fun loadArea(area: String) {
        currentArea = area
        val pfsPrefs = getSharedPreferences("pfs_prefs", Context.MODE_PRIVATE)
        pfsPrefs.edit { putString("pfs_last_area", area) }
        tvCustomTitle.text = "PFS - $area"
        
        if(drawerLayout.isDrawerOpen(GravityCompat.START)) {
             drawerLayout.closeDrawer(GravityCompat.START)
        }
        
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { 
                pbPfs.visibility = View.VISIBLE 
                rvPfs.visibility = View.GONE
            }
            
            var rawText: String? = null
            var isOffline = false
            
            try {
                for (region in configManager.ITALIAN_REGIONS) {
                    val cachedFile = java.io.File(filesDir, "$region.json")
                    var inputStream: java.io.InputStream? = null
                    
                    if (cachedFile.exists()) {
                        inputStream = cachedFile.inputStream()
                    } else {
                        try {
                            inputStream = assets.open("$region.json")
                        } catch (e: Exception) {
                            // File not found in assets
                        }
                    }

                    if (inputStream != null) {
                        var found = false
                        try {
                            android.util.JsonReader(java.io.InputStreamReader(inputStream, "UTF-8")).use { reader ->
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    val topLevelKey = reader.nextName()
                                    if (topLevelKey.equals(region, ignoreCase = true)) {
                                        reader.beginObject()
                                        searchLoop@ while (reader.hasNext()) {
                                            val sectionKey = reader.nextName()
                                            if (sectionKey == "Comuni" || sectionKey == "Macrozone") {
                                                reader.beginObject()
                                                while (reader.hasNext()) {
                                                    val cityName = reader.nextName()
                                                    if (cityName == area) {
                                                        val pfsLines = mutableListOf<String>()
                                                        var title = area
                                                        
                                                        reader.beginArray()
                                                        while (reader.hasNext()) {
                                                            val entry = reader.nextString()
                                                            if (sectionKey == "Macrozone" && entry.startsWith("<") && entry.endsWith(">")) {
                                                                title = "$area (${entry.removeSurrounding("<", ">")})"
                                                            } else {
                                                                pfsLines.add(entry)
                                                            }
                                                        }
                                                        reader.endArray()
                                                        
                                                        if (sectionKey == "Macrozone") {
                                                            withContext(Dispatchers.Main) {
                                                                tvCustomTitle.text = "PFS - $title"
                                                            }
                                                        }
                                                        
                                                        rawText = pfsLines.joinToString("\n")
                                                        found = true
                                                        break@searchLoop
                                                    } else {
                                                        reader.skipValue()
                                                    }
                                                }
                                                reader.endObject()
                                            } else {
                                                reader.skipValue()
                                            }
                                        }
                                        break
                                    } else {
                                        reader.skipValue()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        
                        if (found) {
                            break // Found the area, stop searching other regions
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Fallback al vecchio metodo .txt per aree non presenti nel JSON
            if (rawText == null) {
                try {
                    val url = URL("https://raw.githubusercontent.com/gmaclol/Technicalwork-Materiali/master/lists/$area.txt")
                    rawText = url.readText()
                    openFileOutput("pfs_cache_$area.txt", Context.MODE_PRIVATE).use { 
                        it.write(rawText!!.toByteArray()) 
                    }
                } catch (_: Exception) {
                    try {
                        openFileInput("pfs_cache_$area.txt").use {
                            rawText = it.bufferedReader().readText()
                            isOffline = true
                        }
                    } catch (_: Exception) {
                        rawText = null
                    }
                }
            }

            if (rawText != null) {
                val parsedItems = parsePfsList(rawText!!)
                val (lat, lng) = getLastLocation()
                withContext(Dispatchers.Main) {
                    adapter.updateData(parsedItems, lat, lng)
                    pbPfs.visibility = View.GONE
                    rvPfs.visibility = View.VISIBLE
                    if (isOffline) {
                        Toast.makeText(this@PfsActivity, "Modalità offline: caricata cache locale", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    pbPfs.visibility = View.GONE
                    Toast.makeText(this@PfsActivity, "Errore: lista non disponibile offline", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun parsePfsList(text: String): List<PfsItem> {
        val list = mutableListOf<PfsItem>()
        // Aggiunge sempre in automatico la riga per segnalare PFS mancanti
        list.add(PfsItem("Segnala il PFS mancante!!", "00", true))
        
        val parsed = text.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                // Evita duplicati se la riga è già presente nel file (es. nei vecchi .txt)
                if (line.contains("Segnala il PFS mancante!!", ignoreCase = true)) return@mapNotNull null
                
                if (line.contains("::::")) {
                    val parts = line.split("::::")
                    if (parts.size >= 2) PfsItem(parts[0].trim(), parts[1].trim(), true) else null
                } else if (line.contains("::")) {
                    val parts = line.split("::")
                    if (parts.size >= 2) PfsItem(parts[0].trim(), parts[1].trim(), false) else null
                } else null
            }
        
        list.addAll(parsed)
        return list
    }

    override fun onResume() {
        super.onResume()
        // Svuota la coda offline al ritorno in attività
        lifecycleScope.launch(Dispatchers.IO) {
            PfsSyncQueue().flush(this@PfsActivity)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun logPfsClick(item: PfsItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            val (lat, lng) = getLastLocation()
            if (lat == null || lng == null) return@launch // Don't log if location unknown
            
            val settingsRepo = SettingsRepository(this@PfsActivity)
            val techName = settingsRepo.technicianName ?: "Sconosciuto"
            val timestamp = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(Date())
            val timestampRaw = System.currentTimeMillis()
            
            val data = hashMapOf<String, Any>(
                "nome_pfs" to item.name,
                "indirizzo_pfs" to item.address.replace("[", "").replace("]", ""),
                "tecnico" to techName,
                "orario" to timestamp,
                "timestamp_raw" to timestampRaw,
                "lat" to lat,
                "lng" to lng
            )
            
            if (!isNetworkAvailable()) {
                PfsSyncQueue().save(this@PfsActivity, "LOG", data)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PfsActivity, "Offline: Log salvato in coda", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            try {
                // Pulizia preventiva (mantieni max 29 per far spazio al 30esimo)
                cleanupOldEntries("pfs_logs", techName)
                
                Firebase.firestore
                    .collection("pfs_logs")
                    .add(data)
            } catch (_: Exception) {
                // Silenzioso, non disturbiamo l'utente per un log fallito
            }
        }
    }

    private suspend fun cleanupOldEntries(collection: String, techName: String) {
        try {
            val db = Firebase.firestore
            val snapshots = db.collection(collection)
                .whereEqualTo("tecnico", techName)
                .orderBy("timestamp_raw", com.google.firebase.firestore.Query.Direction.ASCENDING)
                .get()
                .await()

            if (snapshots.size() >= 30) {
                // Se abbiamo 30 o più, cancelliamo i più vecchi lasciandone 29
                val toDeleteCount = snapshots.size() - 29
                for (i in 0 until toDeleteCount) {
                    db.collection(collection).document(snapshots.documents[i].id).delete().await()
                }
            }
        } catch (_: Exception) {
            // Se fallisce il cleanup amen, non blocchiamo l'inserimento
        }
    }

    private fun submitMissingAddress(item: PfsItem, newAddress: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val (lat, lng) = getLastLocation()
            val settingsRepo = SettingsRepository(this@PfsActivity)
            val techName = settingsRepo.technicianName ?: "Sconosciuto"
            val timestamp = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date())
            val timestampRaw = System.currentTimeMillis()
            
            val data = hashMapOf<String, Any>(
                "nome_pfs" to item.name,
                "nuovo_indirizzo" to newAddress,
                "comune" to currentArea,
                "tecnico" to techName,
                "orario" to timestamp,
                "timestamp_raw" to timestampRaw
            )
            lat?.let { data["lat"] = it }
            lng?.let { data["lng"] = it }
            
            if (!isNetworkAvailable()) {
                PfsSyncQueue().save(this@PfsActivity, "SIGNAL", data)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PfsActivity, "Offline: Segnalazione messa in coda", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            try {
                AuthManager.ensureAuthenticated()
                cleanupOldEntries("pfs_segnalati", techName)

                Firebase.firestore
                    .collection("pfs_segnalati")
                    .add(data)
                    .await()
                    
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PfsActivity, "Indirizzo inviato con successo!", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
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
        } catch (_: Exception) {
            null to null
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.pfs_menu, menu)
        
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? androidx.appcompat.widget.SearchView
        
        searchView?.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchView.clearFocus()
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter(newText ?: "")
                return true
            }
        })
        
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                drawerLayout.openDrawer(GravityCompat.START)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    private fun setupDynamicDrawer() {
        val container = findViewById<android.widget.LinearLayout>(R.id.llPfsAreasContainer) ?: return
        container.removeAllViews()

        val areas = configManager.getPfsAreas().toMutableList()
        val favPrefs = getSharedPreferences("GeoNavPrefs", Context.MODE_PRIVATE)
        favPrefs.all.forEach { (key, value) ->
            if (key.startsWith("fav_") && value == true) {
                val favName = key.removePrefix("fav_")
                if (!areas.contains(favName)) areas.add(favName)
            }
        }

        areas.forEachIndexed { index, area ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index == 0) topMargin = (8 * resources.displayMetrics.density).toInt()
                    bottomMargin = (4 * resources.displayMetrics.density).toInt()
                }
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val button = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    (56 * resources.displayMetrics.density).toInt(),
                    1f
                )
                text = area
                cornerRadius = (28 * resources.displayMetrics.density).toInt()
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setOnClickListener { loadArea(area) }
            }

            val starBtn = android.widget.ImageButton(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (48 * resources.displayMetrics.density).toInt(),
                    (48 * resources.displayMetrics.density).toInt()
                ).apply {
                    marginStart = (8 * resources.displayMetrics.density).toInt()
                }
                setImageResource(android.R.drawable.star_on)
                setColorFilter(android.graphics.Color.parseColor("#FFD700"))
                setBackgroundResource(android.R.color.transparent)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE

                setOnClickListener {
                    FavoriteManager.setFavorite(this@PfsActivity, area, false)
                    setupDynamicDrawer()
                    FavoriteManager.persistFavoritesToFirebase(
                        this@PfsActivity,
                        settingsRepository,
                        lifecycleScope
                    )
                }
            }

            row.addView(button)
            row.addView(starBtn)
            container.addView(row)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dashboardListener?.remove()
    }
}
