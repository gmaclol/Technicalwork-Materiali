package com.technicalwork.materiali

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.firebase.firestore.ListenerRegistration

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ExcelDataAdapter
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var tvCurrentFileName: TextView
    private lateinit var tvTechName: TextView
    private lateinit var toolbar: Toolbar
    private lateinit var cbIncludeTechName: CheckBox
    private lateinit var cbIncludeDate: CheckBox
    private lateinit var progressBar: ProgressBar
    private var currentFileUri: Uri? = null
    private var consumoFileUri: Uri? = null
    private var isConsumoMode = false
    private val viewModel: MainViewModel by viewModels()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var fileStorageManager: FileStorageManager
    private lateinit var consumoRepository: ConsumoRepository
    private var customToolbarTitle: TextView? = null

    private var saveMenuItem: MenuItem? = null
    private var lastSelectedCompany: String? = null
    private var startupSyncStarted = false
    private var favoritesListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null
    private var exchangeListenerRegistration: ListenerRegistration? = null
    private val exchangeRepo = ExchangeRepository()
    private lateinit var configManager: ConfigManager
    private var toolbarQrButton: View? = null
    // Guard: IDs degli scambi attualmente in fase di processamento, per evitare duplicati
    private val processingExchangeIds = mutableSetOf<String>()

    // updateCheckHandler è stato spostato in MyApplication

    private val selectFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            uri?.let { 
                lastSelectedCompany?.let { company -> saveCompanyFileUri(company, it) }
                saveLastFileUri(it)
                openExcelFile(it)
                drawerLayout.closeDrawer(GravityCompat.START)
            }
        }
    }

    private val selectConsumoFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            uri?.let { 
                saveConsumoFileUri(it)
                openConsumoFile(it)
                drawerLayout.closeDrawer(GravityCompat.START)
            }
        }
    }

    private val createTemplateLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            uri?.let { newUri ->
                viewModel.createTemplate(newUri, lastSelectedCompany) { success ->
                    if (success) {
                        lastSelectedCompany?.let { company -> saveCompanyFileUri(company, newUri) }
                        saveLastFileUri(newUri)
                        openExcelFile(newUri)
                        Toast.makeText(this@MainActivity, getString(R.string.toast_template_created), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_template_error), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val createConsumoTemplateLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            uri?.let { newUri ->
                lifecycleScope.launch {
                    val res = consumoRepository.createFromSample(newUri, getTechnicianName() ?: "")
                    if (res.isSuccess) {
                        saveConsumoFileUri(newUri)
                        openConsumoFile(newUri)
                        Toast.makeText(this@MainActivity, "File consumi creato", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Errore creazione file consumi", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    private val exchangeActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Ricarica il file corrente per riflettere le modifiche post-scambio
            currentFileUri?.let { uri ->
                if (isConsumoMode) openConsumoFile(uri) else openExcelFile(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        
        // --- ROUTING LOGIC ---
        val bannedPrefs = getSharedPreferences("com.technicalwork.materiali.favorites", Context.MODE_PRIVATE)
        if (bannedPrefs.getBoolean("is_banned", false)) {
            val bannedIntent = Intent(this, BannedActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(bannedIntent)
            finish()
            return
        }

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val lastActivity = prefs.getString("last_activity", "MainActivity")
        if (lastActivity == "PfsActivity" && !intent.getBooleanExtra("skip_routing", false)) {
            val routingIntent = Intent(this, PfsActivity::class.java)
            routingIntent.putExtra("skip_routing", true)
            startActivity(routingIntent)
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        // Initialize default favorites if not done
        FavoriteManager.initializeDefaultFavoritesIfNeeded(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        settingsRepository = SettingsRepository(this)
        fileStorageManager = FileStorageManager(this)
        consumoRepository = ConsumoRepository(this)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        
        // Setup QR button
        toolbarQrButton = toolbar.findViewById(R.id.qr_button_container)
        toolbarQrButton?.setOnClickListener {
            showExchangeChoiceSheet()
        }

        // Ottimizzazione Titolo Toolbar
        customToolbarTitle = toolbar.findViewById(R.id.customToolbarTitle)
        customToolbarTitle?.isSelected = true // Attiva il marquee

        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        configManager = ConfigManager(this)
        setupDynamicDrawer()

        drawerLayout = findViewById(R.id.drawerLayout)
        recyclerView = findViewById(R.id.recyclerView)
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val bottomPadding = maxOf(imeInsets.bottom, navInsets.bottom)
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottomPadding)
            (view as? RecyclerView)?.clipToPadding = false

            if (imeInsets.bottom > 0) {
                val focusedView = recyclerView.findFocus()
                focusedView?.let {
                    recyclerView.post {
                        val position = recyclerView.getChildAdapterPosition(recyclerView.findContainingItemView(it) ?: return@post)
                        if (position != RecyclerView.NO_ID.toInt()) recyclerView.smoothScrollToPosition(position)
                    }
                }
            }
            insets
        }
        progressBar = findViewById(R.id.progressBar)
        val navigationView: NavigationView = findViewById(R.id.navigationView)

        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(0, systemBars.top, 0, 0)
            // rimosso il setPadding della recyclerView qui perche lo gestiamo sopra con imeInsets
            navigationView.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        
        val headerView = navigationView.getHeaderView(0)
        tvCurrentFileName = headerView.findViewById(R.id.tvCurrentFileName)
        
        // Configurazione globale Drawer (Header + Bottoni aziendali)
        setupDynamicDrawer()

        tvTechName = findViewById(R.id.tvTechName)
        cbIncludeTechName = findViewById(R.id.cbIncludeTechName)
        cbIncludeDate = findViewById(R.id.cbIncludeDate)

        cbIncludeTechName.isChecked = settingsRepository.includeTechName
        cbIncludeDate.isChecked = settingsRepository.includeDate

        cbIncludeTechName.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.includeTechName = isChecked
        }
        cbIncludeDate.setOnClickListener { /* per compatibilità UI */ }
        cbIncludeDate.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.includeDate = isChecked
        }

        tvTechName.setOnClickListener {
            showTechnicianNameDialog(true)
        }

        val btnConsumo: MaterialButton = findViewById(R.id.navBtnConsumo)
        val btnAddRow: MaterialButton = findViewById(R.id.navBtnAddRow)
        val btnResetFile: MaterialButton = findViewById(R.id.navBtnResetFile)

        adapter = ExcelDataAdapter(mutableListOf()) {
            if (isConsumoMode) {
                viewModel.saveStateForUndo(adapter.getData())
                return@ExcelDataAdapter
            }
            // Callback eseguito quando i dati cambiano (perdita focus, riga aggiunta/rimossa, +/-)
            val masterList = AssetsHelper().loadMasterList(this, lastSelectedCompany)
            val currentData = adapter.getData().map { Pair(it.label, it.value) }
            val mergedPairs = MaterialMerger().merge(currentData, masterList, filterEmptyExtras = false)
            val finalData = mergedPairs.map { ExcelRowData(it.first, it.second) }
            
            // Applica il merge reattivo solo se necessario per evitare loop
            if (finalData != adapter.getData()) {
                adapter.updateData(finalData)
            }
            
            // Reset safety net su modifica
            if (viewModel.preRevertSnapshot != null) {
                viewModel.preRevertSnapshot = null
                updateUndoButtonLook()
            }
            
            // Salva sempre lo stato per l'Undo
            viewModel.saveStateForUndo(finalData)
        }
        if (!isConsumoMode) {
            adapter.setMasterList(AssetsHelper().loadMasterList(this, lastSelectedCompany))
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                showDeleteConfirmation(viewHolder.bindingAdapterPosition)
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(recyclerView)

        // I bottoni delle aziende vengono ora generati dinamicamente in setupDynamicDrawer()
        
        btnConsumo.setOnClickListener {
            handleConsumoClick()
        }

        btnConsumo.setOnLongClickListener {
            val options = arrayOf(getString(R.string.menu_rename_file), getString(R.string.menu_change_file), getString(R.string.menu_reset))
            AlertDialog.Builder(this)
                .setTitle("Materiali di consumo")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val uri = consumoFileUri ?: settingsRepository.consumoFileUri?.toUri()
                            uri?.let { showRenameDialog("Consumo", it) }
                        }
                        1 -> {
                            showConsumoChoiceDialog()
                        }
                        2 -> {
                            val uri = consumoFileUri ?: settingsRepository.consumoFileUri?.toUri()
                            if (uri != null) {
                                currentFileUri = uri
                                showResetConfirmationDialog()
                            }
                        }
                    }
                }
                .show()
            true
        }

        btnAddRow.setOnClickListener {
            adapter.addRow()
            recyclerView.smoothScrollToPosition(0) // Scorri all'inizio perché aggiungiamo in cima
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        btnResetFile.setOnClickListener {
            if (currentFileUri != null) {
                showResetConfirmationDialog()
            } else {
                Toast.makeText(this, getString(R.string.toast_no_file_open), Toast.LENGTH_SHORT).show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else if (viewModel.hasUnsavedChanges.value) {
                    showExitWarningDialog()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState != null) {
            isConsumoMode = savedInstanceState.getBoolean("isConsumoMode", false)
            lastSelectedCompany = savedInstanceState.getString("lastSelectedCompany")
            val uriStr = savedInstanceState.getString("currentFileUri")
            currentFileUri = uriStr?.toUri()
            
            adapter.isConsumoMode = isConsumoMode
            val fileNameWithExt = currentFileUri?.let { fileStorageManager.getFileNameFromUri(it, if (isConsumoMode) "Materiali di consumo" else getString(R.string.default_file_name)) }
            val fileName = fileNameWithExt?.substringBeforeLast('.')
            customToolbarTitle?.text = fileName
            tvCurrentFileName.text = if (isConsumoMode) "Materiali di consumo" else (lastSelectedCompany ?: getString(R.string.default_company_name))
            toolbarQrButton?.visibility = if (isConsumoMode) View.GONE else View.VISIBLE
            
            if (!isConsumoMode) {
                adapter.setMasterList(AssetsHelper().loadMasterList(this, lastSelectedCompany))
            }
        } else {
            loadLastFile()
        }
        checkTechnicianName()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest { state ->
                        handleUiState(state)
                    }
                }
                launch {
                    viewModel.hasUnsavedChanges.collectLatest { hasChanges ->
                        updateSaveButtonLook(hasChanges)
                    }
                }
            }
        }

        // Il Controllo Aggiornamenti globale è ora gestito in MyApplication

        // Richiesta permessi posizione e camera all'avvio
        requestPermissionLauncher.launch(arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.CAMERA
        ))

        // L'aggiornamento posizione e presenza all'avvio è ora gestito globalmente da MyApplication

        // Listener per rinomina remota e aggiornamento preferiti PFS dalla Dashboard
        favoritesListenerRegistration = FavoriteManager.attachDashboardListener(
            context = this,
            settingsRepo = settingsRepository
        ) { newName, newFavorites ->
            var updated = false
            if (!newName.isNullOrBlank() && newName != getTechnicianName()) {
                saveTechnicianName(newName)
                tvTechName.text = newName
                updated = true
            }
            if (newFavorites != null) {
                setupDynamicDrawer()
                updated = true
            }
            if (updated) {
                lifecycleScope.launch { performTotalSync(force = true) }
            }
        }

        // Listener real-time per scambi materiale in arrivo
        val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        setupExchangeListener(deviceId)
    }
    

    private fun performTotalSync(force: Boolean = false) {
        if (startupSyncStarted && !force) return
        startupSyncStarted = true
        SyncWorker.enqueue(this, isFullSync = force)
    }

    override fun onResume() {
        super.onResume()
        
        // Svuota la coda offline; il sync periodico è già gestito da performTotalSync()
        lifecycleScope.launch(Dispatchers.IO) {
            SyncQueue().flush(this@MainActivity, FirebaseRepository())
        }

        // Check scambi pendenti al resume
        processPendingExchanges()
        
        // Controlla se le liste sono state aggiornate da MyApplication (Forza Aggiornamento)
        checkForListsUpdate()
    }
    
    /**
     * Controlla se MyApplication ha aggiornato le liste in background.
     * Se sì, invalida la cache del ConfigManager, ricostruisce il drawer,
     * e ri-valida l'azienda selezionata.
     */
    private fun checkForListsUpdate() {
        val syncPrefs = getSharedPreferences("sync_meta", Context.MODE_PRIVATE)
        val lastUpdateAt = syncPrefs.getLong("lists_updated_at", 0L)
        val lastCheckedAt = syncPrefs.getLong("lists_checked_by_main", 0L)
        
        if (lastUpdateAt > lastCheckedAt) {
            Log.i("TW_MainActivity", "Rilevato aggiornamento liste in background (update=$lastUpdateAt > checked=$lastCheckedAt)")
            syncPrefs.edit().putLong("lists_checked_by_main", lastUpdateAt).apply()
            
            // Invalida la cache in memoria del ConfigManager per leggere il nuovo config_cache.json
            configManager.invalidateCache()
            
            // Ricostruisci il drawer con le nuove aziende
            setupDynamicDrawer()
            
            // Ri-valida l'azienda selezionata
            if (lastSelectedCompany != null && lastSelectedCompany != "Consumo") {
                val validCompanies = configManager.getCompanies()
                val companyFile = java.io.File(filesDir, "lists/${lastSelectedCompany}.txt")
                
                if (!validCompanies.contains(lastSelectedCompany) || !companyFile.exists()) {
                    val staleName = lastSelectedCompany
                    Log.i("TW_MainActivity", "Azienda '$staleName' rimossa o file non esistente dopo aggiornamento forzato, reset")
                    lastSelectedCompany = null
                    settingsRepository.lastSelectedCompany = null
                    
                    if (companyFile.exists()) companyFile.delete()
                    
                    // Ricarica con la master list generica
                    currentFileUri?.let { uri ->
                        if (fileStorageManager.isUriAccessible(uri)) {
                            adapter.setMasterList(AssetsHelper().loadMasterList(this, null))
                            readExcelFile(uri)
                        }
                    }
                } else {
                    // L'azienda esiste ed il file c'è, ma la lista potrebbe essere aggiornata — ricarica
                    currentFileUri?.let { uri ->
                        if (fileStorageManager.isUriAccessible(uri) && !isConsumoMode) {
                            adapter.setMasterList(AssetsHelper().loadMasterList(this, lastSelectedCompany))
                            readExcelFile(uri)
                        }
                    }
                }
            }
        }
    }

    /**
     * Chiamato direttamente da MyApplication quando il listener Firestore rileva
     * un aggiornamento forzato delle liste. Invalida la cache e ri-valida in tempo reale.
     */
    fun onListsUpdatedFromDashboard() {
        Log.i("TW_MainActivity", "Ricevuta notifica di aggiornamento liste in tempo reale da MyApplication")
        checkForListsUpdate()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exchangeListenerRegistration?.remove()
        favoritesListenerRegistration?.remove()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isConsumoMode", isConsumoMode)
        outState.putString("lastSelectedCompany", lastSelectedCompany)
        currentFileUri?.let { outState.putString("currentFileUri", it.toString()) }

        val currentData = adapter.getData()
        if (currentData.isNotEmpty()) {
            if (isConsumoMode) {
                viewModel.saveStateForUndo(currentData)
            } else {
                val masterList = AssetsHelper().loadMasterList(this, lastSelectedCompany)
                val mappedPairs = currentData.map { Pair(it.label, it.value) }
                val mergedPairs = MaterialMerger().merge(mappedPairs, masterList, filterEmptyExtras = false)
                val finalData = mergedPairs.map { ExcelRowData(it.first, it.second) }
                viewModel.saveStateForUndo(finalData)
            }
        }
    }


    private fun handleUiState(state: UiState) {
        progressBar.visibility = if (state is UiState.Loading) View.VISIBLE else View.GONE

        when (state) {
            is UiState.Initial, is UiState.Loading -> { } // future loader
            is UiState.Success -> {
                if (adapter.getData() != state.data) {
                    adapter.updateData(state.data)
                }
            }
            is UiState.Error -> {
                if (state.isInvalidFormat) {
                    showSampleDialog()
                } else {
                    Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkTechnicianName() {
        val name = getTechnicianName()
        if (name == null) {
            showTechnicianNameDialog(false)
        } else {
            tvTechName.text = name
        }
    }

    private fun showTechnicianNameDialog(isUpdate: Boolean) {
        val currentName = getTechnicianName()
        MainActivityDialogs.showTechnicianNameDialog(
            activity = this,
            isUpdate = isUpdate,
            currentName = currentName,
            onSave = { newName ->
                saveTechnicianName(newName)
                val now = System.currentTimeMillis()
                settingsRepository.lastNameUpdateTimestamp = now
                tvTechName.text = newName
                
                // Sincronizza immediatamente il nuovo nome su Firebase (incluso devices_names con timestamp)
                val (lat, lng) = getLastLocation()
                val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
                lifecycleScope.launch(Dispatchers.IO) {
                    FirebaseRepository().updateTechnicianName(deviceId, newName, configManager.getCompanies(), lat, lng)
                    performTotalSync(force = true)
                }
            },
            onCancel = {
                checkTechnicianName()
            }
        )
    }

    private fun getTechnicianName(): String? {
        return settingsRepository.technicianName
    }

    private fun saveTechnicianName(name: String) {
        settingsRepository.technicianName = name
    }

    private fun showCompanyOptionsMenu(company: String) {
        val options = arrayOf(getString(R.string.menu_rename_file), getString(R.string.menu_change_file), getString(R.string.menu_reset))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_manage_company, company))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(company)
                    1 -> showChoiceDialog(company)
                    2 -> {
                        val uri = getCompanyFileUri(company)
                        if (uri != null && fileStorageManager.isUriAccessible(uri)) {
                            currentFileUri = uri
                            showResetConfirmationDialog()
                        } else {
                            Toast.makeText(this, getString(R.string.toast_file_not_found), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun showRenameDialog(company: String, providedUri: Uri? = null) {
        val uri = providedUri ?: getCompanyFileUri(company) ?: return
        val currentFullName = fileStorageManager.getFileNameFromUri(uri)
        
        MainActivityDialogs.showRenameDialog(
            activity = this,
            currentFullName = currentFullName,
            onRename = { newBaseName ->
                val extension = currentFullName.substringAfterLast('.', "")
                val cleanFilename = if (extension.isNotEmpty()) "$newBaseName.$extension" else newBaseName
                
                lifecycleScope.launch {
                    val renamedUri = fileStorageManager.safeRenameFile(uri, cleanFilename)
                    if (renamedUri != null) {
                        if (isConsumoMode && company == "Consumo") {
                            saveConsumoFileUri(renamedUri)
                            consumoFileUri = renamedUri
                        } else {
                            saveCompanyFileUri(company, renamedUri)
                        }
                        
                        if (company == lastSelectedCompany) {
                            currentFileUri = renamedUri
                            val fileName = cleanFilename.substringBeforeLast('.')
                            customToolbarTitle?.text = fileName
                        }
                        
                        Toast.makeText(this@MainActivity, getString(R.string.toast_file_renamed), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_rename_error), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun handleShare() {
        val uri = currentFileUri ?: return
        saveExcelFile(silent = true) {
            Toast.makeText(this, getString(R.string.toast_file_ready_share), Toast.LENGTH_SHORT).show()
            try {
                val originalFullName = fileStorageManager.getFileNameFromUri(uri)
                val baseName = originalFullName.substringBeforeLast('.')
                
                // Il nome base deve essere sempre il nome dell'appalto
                var finalName = if (isConsumoMode) "Materiale di consumo" else (lastSelectedCompany ?: baseName)

                if (cbIncludeTechName.isChecked) {
                    getTechnicianName()?.let {
                        finalName += " $it"
                    }
                }

                if (cbIncludeDate.isChecked) {
                    val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.ITALY)
                    val currentDate = sdf.format(Date())
                    finalName += " $currentDate"
                }
                
                val finalFullName = "$finalName.xlsx"

                if (isConsumoMode) {
                    val finalFile = File(cacheDir, finalFullName)
                    contentResolver.openInputStream(uri)?.use { input ->
                        finalFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    val contentUri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", finalFile)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.intent_chooser_send, finalFullName)))
                    return@saveExcelFile
                }

                // 1. Legge i materiali dal file del tecnico
                val techMaterials = adapter.getData().map { Pair(it.label, it.value) }

                // 2. Carica la lista master dagli assets (specifica per azienda o fallback)
                val masterList = AssetsHelper().loadMasterList(this, lastSelectedCompany)

                // 3. Esegue il merge (Tecnico + Master)
                val mergedList = MaterialMerger().merge(techMaterials, masterList)

                // NB: Il sync Firestore è già gestito da saveExcelFile() che chiama SyncWorker.enqueue()
                // Non duplicare l'enqueue qui per evitare race condition con REPLACE policy.

                // 4. Scrive il nuovo file Excel basato sul template Sample.xlsx
                val generatedFile = ExcelWriter().writeOutput(this, mergedList)
                
                // 5. Rinomina il file temporaneo per avere il nome finale desiderato
                val finalFile = File(cacheDir, finalFullName)
                if (generatedFile.exists()) {
                    if (finalFile.exists()) finalFile.delete()
                    generatedFile.renameTo(finalFile)
                }

                val contentUri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", finalFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.intent_chooser_send, finalFullName)))
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.toast_share_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleCompanyClick(company: String) {
        lastSelectedCompany = company
        val uri = getCompanyFileUri(company)
        if (uri != null && fileStorageManager.isUriAccessible(uri)) {
            saveLastFileUri(uri)
            openExcelFile(uri)
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            showChoiceDialog(company)
        }
    }

    private fun handleConsumoClick() {
        val uriString = settingsRepository.consumoFileUri
        if (uriString != null) {
            val uri = uriString.toUri()
            if (fileStorageManager.isUriAccessible(uri)) {
                openConsumoFile(uri)
                drawerLayout.closeDrawer(GravityCompat.START)
                return
            }
        }
        
        showConsumoChoiceDialog()
    }

    private fun showConsumoChoiceDialog() {
        MainActivityDialogs.showConsumoChoiceDialog(
            activity = this,
            onNewFile = {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    putExtra(Intent.EXTRA_TITLE, "Materiali di consumo.xlsx")
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"))
                }
                createConsumoTemplateLauncher.launch(intent)
            },
            onSelectFile = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                }
                selectConsumoFileLauncher.launch(intent)
            }
        )
    }

    private fun openConsumoFile(uri: Uri) {
        viewModel.clearState()
        settingsRepository.lastMode = "consumo"
        isConsumoMode = true
        adapter.isConsumoMode = true
        currentFileUri = uri
        consumoFileUri = uri
        lastSelectedCompany = "Consumo"
        val fileNameWithExt = fileStorageManager.getFileNameFromUri(uri, "Materiali di consumo")
        val fileName = fileNameWithExt.substringBeforeLast('.')
        customToolbarTitle?.text = fileName
        tvCurrentFileName.text = "Materiali di consumo"
        viewModel.currentCompany = "Consumo"
        toolbarQrButton?.visibility = View.GONE
        
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            val result = consumoRepository.readConsumoFile(uri)
            progressBar.visibility = View.GONE
            if (result.isSuccess) {
                if (!isConsumoMode) return@launch
                val data = result.getOrNull() ?: emptyList()
                viewModel.saveStateForUndo(data)
                viewModel.markAsSaved()
                HistoryRepository(this@MainActivity).cleanOldSnapshots("Consumo")
                saveConsumoFileUri(uri)
                saveLastFileUri(uri)
            } else {
                Toast.makeText(this@MainActivity, "Errore lettura consumi", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showChoiceDialog(company: String) {
        MainActivityDialogs.showChoiceDialog(
            activity = this,
            company = company,
            onNewFile = {
                createFileFromTemplate("$company.xlsx")
            },
            onSelectFile = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                selectFileLauncher.launch(intent)
            }
        )
    }

    private fun createFileFromTemplate(fileName: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_TITLE, fileName)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"))
        }
        createTemplateLauncher.launch(intent)
    }

    private fun updateSaveButtonLook(hasUnsavedChanges: Boolean) {
        saveMenuItem?.actionView?.let { view ->
            val hoverBg = view.findViewById<View>(R.id.hover_background)
            val icon = view.findViewById<ImageView>(R.id.save_icon)
            if (hasUnsavedChanges) {
                hoverBg.background = ContextCompat.getDrawable(this, R.drawable.bg_circle_blue)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.white), android.graphics.PorterDuff.Mode.SRC_IN)
            } else {
                hoverBg.background = null
                icon.clearColorFilter()
            }
        }
    }

    private fun updateUndoButtonLook() {
        val undoItem = toolbar.menu.findItem(R.id.action_undo)
        undoItem?.actionView?.let { view ->
            val hoverBg = view.findViewById<View>(R.id.hover_background)
            val icon = view.findViewById<ImageView>(R.id.undo_icon)
            if (viewModel.preRevertSnapshot != null) {
                hoverBg.background = ContextCompat.getDrawable(this, R.drawable.bg_circle_red)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.white), android.graphics.PorterDuff.Mode.SRC_IN)
            } else {
                hoverBg.background = null
                icon.clearColorFilter()
            }
        }
    }

    private fun showExitWarningDialog() {
        MainActivityDialogs.showExitWarningDialog(
            activity = this,
            onExitWithoutSaving = { finish() },
            onSaveAndExit = { saveExcelFile(silent = true) { finish() } }
        )
    }

    private fun showResetConfirmationDialog() {
        MainActivityDialogs.showResetConfirmationDialog(
            activity = this,
            onConfirm = { resetCurrentFile() }
        )
    }

    private fun resetCurrentFile() {
        val uri = currentFileUri ?: return
        if (isConsumoMode) {
             lifecycleScope.launch {
                val res = consumoRepository.createFromSample(uri, getTechnicianName() ?: "")
                if (res.isSuccess) {
                    openConsumoFile(uri)
                    Toast.makeText(this@MainActivity, getString(R.string.toast_file_reset), Toast.LENGTH_SHORT).show()
                }
             }
        } else {
            viewModel.resetExcelFile(uri, lastSelectedCompany)
            Toast.makeText(this@MainActivity, getString(R.string.toast_file_reset), Toast.LENGTH_SHORT).show()
        }
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun loadLastFile() {
        lastSelectedCompany = settingsRepository.lastSelectedCompany
        
        // Validazione: se l'azienda salvata non esiste più nel config, resettiamo
        if (lastSelectedCompany != null && lastSelectedCompany != "Consumo") {
            val validCompanies = configManager.getCompanies()
            if (!validCompanies.contains(lastSelectedCompany)) {
                val staleName = lastSelectedCompany
                Log.i("TW_MainActivity", "Azienda '$staleName' non più presente in config.json, reset a null")
                lastSelectedCompany = null
                settingsRepository.lastSelectedCompany = null
                // Cancella anche il file locale della lista obsoleta
                val staleFile = java.io.File(filesDir, "lists/${staleName}.txt")
                if (staleFile.exists()) staleFile.delete()
            }
        }
        
        val uriString = settingsRepository.lastFileUri

        uriString?.let {
            val uri = it.toUri()
            if (fileStorageManager.isUriAccessible(uri)) {
                if (settingsRepository.lastMode == "consumo") {
                    openConsumoFile(uri)
                } else {
                    openExcelFile(uri)
                }
            } else {
                Toast.makeText(this, getString(R.string.toast_file_not_found), Toast.LENGTH_LONG).show()
                settingsRepository.clearLastFileUri()
            }
        }
    }

    private fun saveLastFileUri(uri: Uri) {
        settingsRepository.lastSelectedCompany = lastSelectedCompany
        settingsRepository.lastFileUri = uri.toString()
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {}
    }

    private fun saveCompanyFileUri(company: String, uri: Uri) {
        settingsRepository.saveCompanyFileUri(company, uri.toString())
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {}
    }

    private fun saveConsumoFileUri(uri: Uri) {
        consumoFileUri = uri
        settingsRepository.consumoFileUri = uri.toString()
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {}
    }

    private fun getCompanyFileUri(company: String): Uri? {
        val uriString = settingsRepository.getCompanyFileUri(company)
        return uriString?.toUri()
    }

    private fun openExcelFile(uri: Uri) {
        viewModel.clearState()
        settingsRepository.lastMode = "appalto"
        isConsumoMode = false
        adapter.isConsumoMode = false
        currentFileUri = uri
        val fileNameWithExt = fileStorageManager.getFileNameFromUri(uri, getString(R.string.default_file_name))
        val fileName = fileNameWithExt.substringBeforeLast('.')
        tvCurrentFileName.text = lastSelectedCompany ?: getString(R.string.default_company_name)
        customToolbarTitle?.text = fileName
        toolbarQrButton?.visibility = View.VISIBLE
        readExcelFile(uri)
        forceMediaStoreScan()
    }

    private fun readExcelFile(uri: Uri) {
        if (isConsumoMode) return
        viewModel.currentCompany = lastSelectedCompany
        lastSelectedCompany?.let { HistoryRepository(this).cleanOldSnapshots(it) }
        adapter.setMasterList(AssetsHelper().loadMasterList(this, lastSelectedCompany))
        viewModel.loadExcelFile(uri, lastSelectedCompany)
    }

    private fun showSampleDialog() {
        viewModel.clearError()
        MainActivityDialogs.showSampleDialog(
            activity = this,
            onUseTemplate = {
                createFileFromTemplate("${lastSelectedCompany ?: "Sample"}.xlsx")
            }
        )
    }

    private fun showDeleteConfirmation(position: Int) {
        MainActivityDialogs.showDeleteConfirmation(
            activity = this,
            onConfirm = {
                adapter.removeRow(position)
                Toast.makeText(this, getString(R.string.toast_row_deleted), Toast.LENGTH_SHORT).show()
            },
            onCancel = {
                adapter.notifyItemChanged(position)
            }
        )
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        saveMenuItem = menu?.findItem(R.id.action_save)
        saveMenuItem?.actionView?.setOnClickListener { saveMenuItem?.let { onOptionsItemSelected(it) } }
        updateSaveButtonLook(viewModel.hasUnsavedChanges.value)
        
        val undoItem = menu?.findItem(R.id.action_undo)
        val undoView = undoItem?.actionView ?: LayoutInflater.from(this).inflate(R.layout.menu_undo_button, recyclerView, false).also {
            undoItem?.actionView = it
        }
        undoView.setOnClickListener { performUndo() }
        undoView.setOnLongClickListener { 
            showHistoryBottomSheet()
            true
        }
        updateUndoButtonLook()
        
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_share -> { handleShare(); true }
            R.id.action_undo -> { performUndo(); true }
            R.id.action_save -> { 
                hideKeyboard()
                recyclerView.clearFocus()
                saveExcelFile()
                true 
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun saveExcelFile(silent: Boolean = false, onComplete: (() -> Unit)? = null) {
        val uri = currentFileUri ?: return

        if (isConsumoMode) {
            lifecycleScope.launch {
                val result = consumoRepository.saveConsumoFile(uri, adapter.getData(), getTechnicianName() ?: "")
                if (result.isSuccess) {
                    viewModel.markAsSaved()
                    viewModel.clearPreRevertSnapshot()
                    updateUndoButtonLook()
                    if (!silent) Toast.makeText(this@MainActivity, getString(R.string.toast_file_saved), Toast.LENGTH_SHORT).show()
                    saveLastFileUri(uri)
                    onComplete?.invoke()

                    // Sync Firestore istantaneo best-effort
                    val techName = getTechnicianName()
                    if (techName != null) {
                        val (lat, lng) = getLastLocation()
                        val deviceId = getDeviceID()
                        val currentData = adapter.getData().map { it.copy() }
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                FirebaseRepository().syncToFirestore(
                                    context = this@MainActivity,
                                    company = "Consumo",
                                    technicianName = techName,
                                    materials = currentData,
                                    lat = lat,
                                    lng = lng,
                                    deviceId = deviceId
                                )
                            } catch (e: Exception) {
                                Log.w("TW_MainActivity", "Sync Firestore istantaneo fallito per Consumo: ${e.message}")
                            }
                        }
                    }

                    SyncWorker.enqueue(this@MainActivity)
                } else {
                    if (!silent) Toast.makeText(this@MainActivity, getString(R.string.toast_save_error), Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        lifecycleScope.launch {
            val masterList = withContext(Dispatchers.IO) {
                AssetsHelper().loadMasterList(this@MainActivity, lastSelectedCompany)
            }
            val currentData = adapter.getData().map { Pair(it.label, it.value) }
            val mergedPairs = MaterialMerger().merge(currentData, masterList)
            val dataToSave = mergedPairs.map { ExcelRowData(it.first, it.second) }
            
            viewModel.saveExcelFile(uri, dataToSave) { success ->
                if (success) {
                    viewModel.clearPreRevertSnapshot()
                    updateUndoButtonLook()
                    if (!silent) Toast.makeText(this@MainActivity, getString(R.string.toast_file_saved), Toast.LENGTH_SHORT).show()
                    saveLastFileUri(uri)
                    onComplete?.invoke()

                    // Sync Firestore istantaneo best-effort
                    val company = lastSelectedCompany
                    val techName = getTechnicianName()
                    if (company != null && techName != null) {
                        val (lat, lng) = getLastLocation()
                        val deviceId = getDeviceID()
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                FirebaseRepository().syncToFirestore(
                                    context = this@MainActivity,
                                    company = company,
                                    technicianName = techName,
                                    materials = dataToSave,
                                    lat = lat,
                                    lng = lng,
                                    deviceId = deviceId
                                )
                            } catch (e: Exception) {
                                Log.w("TW_MainActivity", "Sync Firestore istantaneo fallito per $company: ${e.message}")
                            }
                        }
                    }

                    SyncWorker.enqueue(this@MainActivity)
                } else {
                    if (!silent) Toast.makeText(this@MainActivity, getString(R.string.toast_save_error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // updateCellValue moved to repository

    private fun performUndo() {
        if (viewModel.preRevertSnapshot != null) {
            adapter.updateData(viewModel.preRevertSnapshot!!)
            viewModel.preRevertSnapshot = null
            updateUndoButtonLook()
            Toast.makeText(this, "Ripristinato stato pre-revert", Toast.LENGTH_SHORT).show()
            return
        }

        val success = viewModel.performUndo { restoredData ->
            adapter.updateData(restoredData)
            updateUndoButtonLook()
        }
        if (success) {
            Toast.makeText(this, getString(R.string.toast_undo), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showHistoryBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_history, null)
        val rvHistory = view.findViewById<RecyclerView>(R.id.rvHistory)
        
        val historyList = viewModel.undoStack.toList().reversed()
        val displayList = mutableListOf<HistoryAdapterItem>()
        
        val rawDiffs = mutableListOf<Triple<Int, List<DiffItem>, UndoSnapshot>>()
        for (i in 0 until historyList.size - 1) {
            val currentSnapshot = historyList[i]
            val previousSnapshot = historyList[i+1]
            val diffs = getStructuredDiff(previousSnapshot.data, currentSnapshot.data)
            if (diffs.isNotEmpty()) {
                rawDiffs.add(Triple(viewModel.undoStack.size - 1 - i, diffs, currentSnapshot))
            }
        }

        val tempItems = mutableListOf<HistoryAdapterItem.SnapshotItem>()
        var i = 0
        while (i < rawDiffs.size) {
            var endSnapshotIndex = i
            val accumulatedDiffs = mutableMapOf<String, DiffItem>()
            rawDiffs[i].second.forEach { accumulatedDiffs[it.label] = it }
            
            while (endSnapshotIndex + 1 < rawDiffs.size) {
                val nextDiffs = rawDiffs[endSnapshotIndex + 1].second
                val currentLabels = accumulatedDiffs.keys
                val nextLabels = nextDiffs.map { it.label }.toSet()
                if (currentLabels == nextLabels) {
                    nextDiffs.forEach { nd ->
                        val ext = accumulatedDiffs[nd.label]!!
                        accumulatedDiffs[nd.label] = DiffItem(
                            nd.label,
                            ext.diff + nd.diff,
                            ext.isTextDiff || nd.isTextDiff,
                            ext.header,
                            ext.oldValue,
                            nd.newValue
                        )
                    }
                    endSnapshotIndex++
                } else {
                    break
                }
            }
            
            val targetStackIndex = rawDiffs[endSnapshotIndex].first
            val snapshot = rawDiffs[i].third
            
            val finalDiffs = accumulatedDiffs.values.filter { it.isTextDiff || it.diff != 0 }.toList()
            if (finalDiffs.isNotEmpty()) {
                tempItems.add(HistoryAdapterItem.SnapshotItem(finalDiffs, targetStackIndex, snapshot.timestamp, snapshot.epochMillis))
            }
            i = endSnapshotIndex + 1
        }

        // Raggruppa per Giorno e costruisce la displayList finale piatta
        var lastDay = ""
        for (item in tempItems) {
            val day = getDayString(item.epochMillis)
            if (day != lastDay) {
                displayList.add(HistoryAdapterItem.DayHeader(day))
                lastDay = day
            }
            displayList.add(item)
        }

        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            private val TYPE_HEADER = 0
            private val TYPE_ITEM = 1

            override fun getItemViewType(position: Int): Int {
                return when (displayList[position]) {
                    is HistoryAdapterItem.DayHeader -> TYPE_HEADER
                    is HistoryAdapterItem.SnapshotItem -> TYPE_ITEM
                }
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history_row, parent, false)
                return if (viewType == TYPE_HEADER) HeaderViewHolder(v) else HistoryViewHolder(v)
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                when (val item = displayList[position]) {
                    is HistoryAdapterItem.DayHeader -> {
                        val hHolder = holder as HeaderViewHolder
                        hHolder.tvHeader.text = item.day
                        hHolder.tvHeader.setTypeface(null, android.graphics.Typeface.BOLD)
                        hHolder.tvHeader.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gemini_accent_blue))
                        hHolder.tvHeader.textSize = 16f
                        hHolder.containerGroups.visibility = View.GONE
                        hHolder.itemView.setOnClickListener(null)
                    }
                    is HistoryAdapterItem.SnapshotItem -> {
                        val iHolder = holder as HistoryViewHolder
                        iHolder.tvTime.text = "⏱️ Modifiche delle ore ${item.timestamp.substringBeforeLast(':')}"
                        iHolder.tvTime.textSize = 12f
                        iHolder.tvTime.setTypeface(null, android.graphics.Typeface.BOLD)
                        iHolder.tvTime.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gemini_text_secondary))
                        
                        iHolder.containerGroups.visibility = View.VISIBLE
                        iHolder.containerGroups.removeAllViews()

                        val byHeader = item.diffs.groupBy { it.header ?: "Senza Categoria" }
                        val blue = ContextCompat.getColor(this@MainActivity, R.color.gemini_accent_blue)
                        val red = ContextCompat.getColor(this@MainActivity, R.color.gemini_destructive)
                        val gray = Color.GRAY

                        for ((header, diffList) in byHeader) {
                            val card = androidx.cardview.widget.CardView(this@MainActivity).apply {
                                val lp = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    setMargins(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
                                }
                                layoutParams = lp
                                radius = (8 * resources.displayMetrics.density)
                                cardElevation = (3 * resources.displayMetrics.density)
                                setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.gemini_card_bg))
                            }

                            val innerLayout = LinearLayout(this@MainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                                orientation = LinearLayout.VERTICAL
                                val padding = (12 * resources.displayMetrics.density).toInt()
                                setPadding(padding, padding, padding, padding)
                            }

                            val tvHeader = TextView(this@MainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    setMargins(0, 0, 0, (6 * resources.displayMetrics.density).toInt())
                                }
                                text = "📁 $header"
                                textSize = 14f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gemini_text_main))
                            }
                            innerLayout.addView(tvHeader)

                            for (diffItem in diffList) {
                                val tvItem = TextView(this@MainActivity).apply {
                                    layoutParams = LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT
                                    ).apply {
                                        setMargins((8 * resources.displayMetrics.density).toInt(), 0, 0, (4 * resources.displayMetrics.density).toInt())
                                    }
                                    textSize = 13f
                                    
                                    val textBuilder = SpannableStringBuilder().apply {
                                        append("• ").append(diffItem.label).append(": ")
                                        if (diffItem.isTextDiff) {
                                            val oldText = if (diffItem.oldValue.isEmpty()) "vuoto" else "\"${diffItem.oldValue}\""
                                            val newText = if (diffItem.newValue.isEmpty()) "cancellato" else "\"${diffItem.newValue}\""
                                            append("modificato da ").append(oldText).append(" a ").append(newText)
                                            setSpan(ForegroundColorSpan(gray), 0, length, 0)
                                        } else {
                                            if (diffItem.diff > 0) {
                                                val t = "aggiunti +${diffItem.diff} pezzi (ora: ${diffItem.newValue}, prima: ${diffItem.oldValue})"
                                                val start = length
                                                append(t)
                                                setSpan(ForegroundColorSpan(blue), start, length, 0)
                                                setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, length, 0)
                                            } else {
                                                val t = "tolti ${Math.abs(diffItem.diff)} pezzi (ora: ${diffItem.newValue}, prima: ${diffItem.oldValue})"
                                                val start = length
                                                append(t)
                                                setSpan(ForegroundColorSpan(red), start, length, 0)
                                                setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, length, 0)
                                            }
                                        }
                                    }
                                    text = textBuilder
                                }
                                innerLayout.addView(tvItem)
                            }

                            card.addView(innerLayout)
                            iHolder.containerGroups.addView(card)
                        }

                        iHolder.itemView.setOnClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Sei sicuro?")
                                .setMessage("Vuoi tornare alle modifiche fatte alle ore ${item.timestamp.substringBeforeLast(':')}? Le modifiche inserite dopo andranno perse.")
                                .setPositiveButton("Conferma") { _, _ ->
                                    viewModel.preRevertSnapshot = adapter.getData().map { it.copy() }
                                    while (viewModel.undoStack.size > item.stackIndex + 1) viewModel.undoStack.pop()
                                    val restoredData = viewModel.undoStack.peek().data.map { it.copy() }
                                    adapter.updateData(restoredData)
                                    viewModel.markAsUnsaved()
                                    updateUndoButtonLook()
                                    dialog.dismiss()
                                }
                                .setNegativeButton("Annulla", null)
                                .show()
                        }
                    }
                }
            }

            override fun getItemCount() = displayList.size
        }
        
        dialog.setContentView(view)
        dialog.show()
    }

    private fun getDayString(epochMillis: Long): String {
        val now = java.util.Calendar.getInstance()
        val sms = java.util.Calendar.getInstance()
        sms.timeInMillis = epochMillis
        
        val format = SimpleDateFormat("dd MMMM yyyy", Locale.ITALY)
        val todayStr = format.format(now.time)
        val smsStr = format.format(sms.time)
        
        if (todayStr == smsStr) return "📅 Oggi"
        
        now.add(java.util.Calendar.DATE, -1)
        val yesterdayStr = format.format(now.time)
        if (yesterdayStr == smsStr) return "📅 Ieri"
        
        val dayOfWeekFormat = SimpleDateFormat("EEEE d MMMM yyyy", Locale.ITALY)
        return "📅 " + dayOfWeekFormat.format(sms.time).replaceFirstChar { it.uppercase() }
    }

    private sealed class HistoryAdapterItem {
        data class DayHeader(val day: String) : HistoryAdapterItem()
        data class SnapshotItem(
            val diffs: List<DiffItem>,
            val stackIndex: Int,
            val timestamp: String,
            val epochMillis: Long
        ) : HistoryAdapterItem()
    }

    private data class DiffItem(
        val label: String,
        val diff: Int,
        val isTextDiff: Boolean,
        val header: String?,
        val oldValue: String,
        val newValue: String
    )

    private class HeaderViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvHeader: TextView = v.findViewById(R.id.tvTime)
        val containerGroups: LinearLayout = v.findViewById(R.id.containerGroups)
    }

    private class HistoryViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvTime: TextView = v.findViewById(R.id.tvTime)
        val containerGroups: LinearLayout = v.findViewById(R.id.containerGroups)
    }

    private fun getStructuredDiff(old: List<ExcelRowData>, new: List<ExcelRowData>): List<DiffItem> {
        val newMap = new.associateBy { it.label }
        val oldMap = old.associateBy { it.label }
        val allLabels = (new.map { it.label } + old.map { it.label }).distinct()
        
        val diffs = mutableListOf<DiffItem>()
        for (label in allLabels) {
            if (label.isEmpty() || label.startsWith("::") || label.startsWith(";;")) continue
            val newValStr = newMap[label]?.value ?: ""
            val oldValStr = oldMap[label]?.value ?: ""
            
            if (newValStr != oldValStr) {
                val newVal = newValStr.toIntOrNull()
                val oldVal = oldValStr.toIntOrNull()
                val header = findHeaderForLabel(new, label) ?: findHeaderForLabel(old, label)
                if (newVal != null && oldVal != null) {
                    val diff = newVal - oldVal
                    if (diff != 0) {
                        diffs.add(DiffItem(label, diff, false, header, oldValStr, newValStr))
                    }
                } else {
                    diffs.add(DiffItem(label, 0, true, header, oldValStr, newValStr))
                }
            }
        }
        return diffs
    }

    private fun findHeaderForLabel(data: List<ExcelRowData>, targetLabel: String): String? {
        var lastHeader: String? = null
        for (row in data) {
            val l = row.label.trim()
            if ((l.startsWith("::") && l.endsWith("::")) || (l.startsWith(";;") && l.endsWith(";;"))) {
                lastHeader = l.removeSurrounding("::").removeSurrounding(";;").trim()
            }
            if (row.label == targetLabel) return lastHeader
        }
        return null
    }

    private fun formatGroupedDiff(diffs: List<DiffItem>): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        val blue = ContextCompat.getColor(this, R.color.gemini_accent_blue)
        val red = ContextCompat.getColor(this, R.color.gemini_destructive)
        val gray = Color.GRAY
        val headerColor = ContextCompat.getColor(this, R.color.gemini_text_main)

        val byHeader = diffs.groupBy { it.header ?: "Senza Categoria" }

        var firstHeader = true
        for ((header, items) in byHeader) {
            if (!firstHeader) ssb.append("\n")
            firstHeader = false

            val startH = ssb.length
            ssb.append("📁 ").append(header).append("\n")
            ssb.setSpan(ForegroundColorSpan(headerColor), startH, ssb.length, 0)
            ssb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), startH, ssb.length, 0)
            ssb.setSpan(RelativeSizeSpan(0.95f), startH, ssb.length, 0)

            for (item in items) {
                val startItem = ssb.length
                ssb.append("  • ").append(item.label).append(": ")
                
                if (item.isTextDiff) {
                    val oldText = if (item.oldValue.isEmpty()) "vuoto" else "\"${item.oldValue}\""
                    val newText = if (item.newValue.isEmpty()) "cancellato" else "\"${item.newValue}\""
                    ssb.append("modificato da ").append(oldText).append(" a ").append(newText).append("\n")
                    ssb.setSpan(ForegroundColorSpan(gray), startItem, ssb.length, 0)
                } else {
                    if (item.diff > 0) {
                        val text = "aggiunti +${item.diff} pezzi (ora: ${item.newValue}, prima: ${item.oldValue})\n"
                        val startVal = ssb.length
                        ssb.append(text)
                        ssb.setSpan(ForegroundColorSpan(blue), startVal, ssb.length, 0)
                        ssb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), startVal, ssb.length, 0)
                    } else {
                        val text = "tolti ${Math.abs(item.diff)} pezzi (ora: ${item.newValue}, prima: ${item.oldValue})\n"
                        val startVal = ssb.length
                        ssb.append(text)
                        ssb.setSpan(ForegroundColorSpan(red), startVal, ssb.length, 0)
                        ssb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), startVal, ssb.length, 0)
                    }
                }
            }
        }
        
        if (ssb.isNotEmpty() && ssb.last() == '\n') {
            ssb.delete(ssb.length - 1, ssb.length)
        }
        
        return ssb
    }

    private fun forceMediaStoreScan() {
        val uri = currentFileUri ?: return
        try {
            var path: String? = null
            if (uri.scheme == "file") {
                path = uri.path
            } else {
                contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        path = cursor.getString(0)
                    }
                }
            }
            if (path != null) {
                MediaScannerConnection.scanFile(this, arrayOf(path), null, null)
            }
        } catch (_: Exception) {
            // Ignore, extraction not supported or permission missing
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

    // ==================== SCAMBIO MATERIALE ====================

    private fun showExchangeChoiceSheet() {
        if (currentFileUri == null) {
            Toast.makeText(this, getString(R.string.exchange_no_file), Toast.LENGTH_SHORT).show()
            return
        }

        MainActivityDialogs.showExchangeChoiceSheet(
            activity = this,
            onGenerateQr = {
                val intent = Intent(this, ExchangeActivity::class.java)
                intent.putExtra(ExchangeActivity.EXTRA_MODE, ExchangeActivity.MODE_GENERATE)
                exchangeActivityLauncher.launch(intent)
            },
            onScanQr = {
                val intent = Intent(this, ExchangeActivity::class.java)
                intent.putExtra(ExchangeActivity.EXTRA_MODE, ExchangeActivity.MODE_SCAN)
                exchangeActivityLauncher.launch(intent)
            }
        )
    }

    private fun setupExchangeListener(deviceId: String) {
        exchangeListenerRegistration = exchangeRepo.listenForPendingExchanges(deviceId) { pendingList ->
            lifecycleScope.launch {
                for (exchange in pendingList) {
                    // Skip se già in fase di processamento (race con processPendingExchanges)
                    synchronized(processingExchangeIds) {
                        if (!processingExchangeIds.add(exchange.id)) return@launch
                    }
                    try {
                        processAndMarkExchange(exchange)
                    } finally {
                        synchronized(processingExchangeIds) {
                            processingExchangeIds.remove(exchange.id)
                        }
                    }
                }
            }
        }
    }

    private fun getDeviceID(): String {
        return android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
    }

    private fun processPendingExchanges() {
        val deviceId = getDeviceID()
        lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                exchangeRepo.getPendingExchanges(deviceId)
            }
            for (exchange in pending) {
                // Skip se già in fase di processamento (race con setupExchangeListener)
                synchronized(processingExchangeIds) {
                    if (!processingExchangeIds.add(exchange.id)) continue
                }
                try {
                    processAndMarkExchange(exchange)
                } finally {
                    synchronized(processingExchangeIds) {
                        processingExchangeIds.remove(exchange.id)
                    }
                }
            }
        }
    }

    /**
     * Processa un singolo scambio: applica le modifiche all'inventario locale e marca come processato.
     * Il markAsProcessed avviene subito dopo il salvataggio del file locale, indipendentemente
     * dal successo della sincronizzazione Firestore (che verrà recuperata dal SyncWorker).
     */
    private suspend fun processAndMarkExchange(exchange: ExchangeLog) {
        val success = applyExchangeToLocalInventory(exchange)
        if (success) {
            withContext(Dispatchers.IO) {
                exchangeRepo.markAsProcessed(exchange.id)
            }
        }
    }

    /**
     * Applica uno scambio ricevuto all'inventario locale.
     * Legge il file Excel dell'appalto coinvolto, modifica le quantità, salva.
     * Ritorna true in caso di successo, false in caso di errore.
     */
    private suspend fun applyExchangeToLocalInventory(exchange: ExchangeLog): Boolean {
        val company = exchange.company
        val fileUriString = settingsRepository.getCompanyFileUri(company) ?: return false
        val uri = android.net.Uri.parse(fileUriString)

        if (!fileStorageManager.isUriAccessible(uri)) return false

        val excelRepo = ExcelRepository(this)
        val result = withContext(Dispatchers.IO) { excelRepo.readExcelFile(uri, company) }
        if (result.isFailure) return false

        val localData = result.getOrNull()?.toMutableList() ?: return false
        val parser = StockParser()

        // La direzione è dal punto di vista di B (chi ha scansionato).
        // Per A (noi), l'effetto è inverso:
        // Se B ha preso da A → A perde materiale
        // Se B ha dato ad A → A guadagna materiale
        val direction = try { ExchangeDirection.valueOf(exchange.direction) } catch (_: Exception) { return false }

        val items = exchange.items.mapNotNull { map ->
            val label = map["label"] as? String ?: return@mapNotNull null
            val qtyFree = (map["qtyFree"] as? Number)?.toInt() ?: 0
            val qtyUsed = (map["qtyUsed"] as? Number)?.toInt() ?: 0
            ExchangeItem(label, qtyFree, qtyUsed)
        }

        for (item in items) {
            val index = localData.indexOfFirst { it.label == item.label }
            if (index >= 0) {
                val stock = parser.parse(localData[index].label, localData[index].value)
                val newStock = when (direction) {
                    ExchangeDirection.B_TAKES_FROM_A -> {
                        // B ha preso da noi → noi perdiamo
                        stock.copy(
                            free = (stock.free - item.qtyFree).coerceAtLeast(0),
                            used = (stock.used - item.qtyUsed).coerceAtLeast(0)
                        )
                    }
                    ExchangeDirection.B_GIVES_TO_A -> {
                        // B ci ha dato materiale → noi guadagniamo
                        stock.copy(
                            free = stock.free + item.qtyFree,
                            used = stock.used + item.qtyUsed
                        )
                    }
                }
                localData[index] = ExcelRowData(localData[index].label, parser.recompose(newStock))
            } else if (direction == ExchangeDirection.B_GIVES_TO_A) {
                // Materiale nuovo che B ci ha dato
                val newValue = if (item.qtyUsed > 0) {
                    "${item.qtyFree} + ${item.qtyUsed} sparat"
                } else if (item.qtyFree > 0) {
                    item.qtyFree.toString()
                } else ""
                if (newValue.isNotEmpty()) {
                    localData.add(ExcelRowData(item.label, newValue))
                }
            }
        }

        // Salva il file Excel locale
        val saveSuccess = withContext(Dispatchers.IO) {
            excelRepo.saveExcelFile(uri, localData).isSuccess
        }
        if (!saveSuccess) return false

        // Se l'appalto dello scambio è quello attualmente aperto, ricarica la view completamente in real-time
        if (company == lastSelectedCompany && currentFileUri == uri) {
            viewModel.loadExcelFile(uri, company)
        }

        // Notifica l'utente
        withContext(Dispatchers.Main) {
            Toast.makeText(
                this@MainActivity,
                getString(R.string.exchange_pending_applied, exchange.fromTechName),
                Toast.LENGTH_LONG
            ).show()
        }

        // Sync Firestore in modo best-effort: se fallisce, il SyncWorker recupererà la prossima volta.
        // Non blocchiamo il markAsProcessed per questo — il salvataggio locale è ciò che conta.
        val techName = getTechnicianName()
        if (techName != null) {
            val (lat, lng) = getLastLocation()
            val deviceId = getDeviceID()
            withContext(Dispatchers.IO) {
                try {
                    FirebaseRepository().syncToFirestore(this@MainActivity, company, techName, localData, lat, lng, deviceId = deviceId)
                } catch (e: Exception) {
                    Log.w("TW_MainActivity", "Sync Firestore best-effort fallito per scambio ${exchange.id}: ${e.message}")
                }
            }
        } else {
            // Se manca il techName, programma un sync completo per dopo
            SyncWorker.enqueue(this@MainActivity)
        }

        return true
    }
    private fun setupDynamicDrawer() {
        val navigationView: com.google.android.material.navigation.NavigationView = findViewById(R.id.navigationView)
        MainActivityDrawerHelper.bindDrawer(
            activity = this,
            navigationView = navigationView,
            companies = configManager.getCompanies(),
            onLogoClick = {
                drawerLayout.closeDrawer(GravityCompat.START)
            },
            onPfsLogoClick = {
                val p = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                p.edit { putString("last_activity", "PfsActivity") }
                val forwardIntent = Intent(this, PfsActivity::class.java)
                forwardIntent.putExtra("skip_routing", true)
                startActivity(forwardIntent)
                finish()
            },
            onCompanyClick = { company ->
                handleCompanyClick(company)
            },
            onCompanyLongClick = { company ->
                lastSelectedCompany = company
                showCompanyOptionsMenu(company)
            }
        )
    }
}
