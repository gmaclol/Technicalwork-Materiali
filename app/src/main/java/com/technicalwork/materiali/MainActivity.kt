package com.technicalwork.materiali

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ExcelDataAdapter
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var tvCurrentFileName: TextView
    private lateinit var tvTechName: TextView
    private lateinit var toolbar: Toolbar
    private lateinit var cbIncludeTechName: CheckBox
    private lateinit var cbIncludeDate: CheckBox
    private var currentFileUri: Uri? = null
    private val viewModel: MainViewModel by viewModels()
    private lateinit var settingsRepository: SettingsRepository
    
    private var saveMenuItem: MenuItem? = null
    private var lastSelectedCompany: String? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsRepository = SettingsRepository(this)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        
        // Ottimizzazione Titolo Toolbar
        toolbar.setTitleTextAppearance(this, androidx.appcompat.R.style.TextAppearance_AppCompat_Widget_ActionBar_Title)
        val titleView = toolbar.getChildAt(0) as? TextView
        titleView?.let {
            it.isSingleLine = true
            it.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            it.marqueeRepeatLimit = -1
            it.isSelected = true // Attiva il marquee
            it.setPadding(0, 0, 8, 0)
        }

        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        drawerLayout = findViewById(R.id.drawerLayout)
        recyclerView = findViewById(R.id.recyclerView)
        val navigationView: NavigationView = findViewById(R.id.navigationView)
        
        val headerView = navigationView.getHeaderView(0)
        tvCurrentFileName = headerView.findViewById(R.id.tvCurrentFileName)
        tvTechName = findViewById(R.id.tvTechName)
        cbIncludeTechName = findViewById(R.id.cbIncludeTechName)
        cbIncludeDate = findViewById(R.id.cbIncludeDate)

        cbIncludeTechName.isChecked = settingsRepository.includeTechName
        cbIncludeDate.isChecked = settingsRepository.includeDate

        cbIncludeTechName.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.includeTechName = isChecked
        }
        cbIncludeDate.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.includeDate = isChecked
        }

        tvTechName.setOnClickListener {
            showTechnicianNameDialog(true)
        }

        val btnElecnor: MaterialButton = findViewById(R.id.navBtnElecnor)
        val btnSertori: MaterialButton = findViewById(R.id.navBtnSertori)
        val btnSirti: MaterialButton = findViewById(R.id.navBtnSirti)
        val btnAddRow: MaterialButton = findViewById(R.id.navBtnAddRow)
        val btnResetFile: MaterialButton = findViewById(R.id.navBtnResetFile)

        adapter = ExcelDataAdapter(mutableListOf()) {
            // Callback eseguito quando i dati cambiano (perdita focus, riga aggiunta/rimossa, +/-)
            val masterList = AssetsHelper().loadMasterList(this, lastSelectedCompany)
            val currentData = adapter.getData().map { Pair(it.label, it.value) }
            val mergedPairs = MaterialMerger().merge(currentData, masterList)
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
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                showDeleteConfirmation(viewHolder.adapterPosition)
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(recyclerView)

        setupCompanyButton(btnElecnor, "Elecnor")
        setupCompanyButton(btnSertori, "Sertori")
        setupCompanyButton(btnSirti, "Sirti")

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

        loadLastFile()
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

        // Controllo Aggiornamenti
        val updateManager = UpdateManager(this)
        lifecycleScope.launch {
            updateManager.checkForUpdates { versionName, downloadUrl ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.dialog_title_update_available))
                    .setMessage(getString(R.string.dialog_msg_update_available, versionName))
                    .setPositiveButton(getString(R.string.btn_update)) { _, _ ->
                        updateManager.downloadAndInstall(downloadUrl)
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
        }
    }

    private fun handleUiState(state: UiState) {
        when (state) {
            is UiState.Initial, is UiState.Loading -> { } // future loader
            is UiState.Success -> {
                adapter.updateData(state.data)
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
        val input = EditText(this)
        val currentName = getTechnicianName()
        if (isUpdate && currentName != null) {
            input.setText(currentName)
            input.setSelectAllOnFocus(true)
        }

        AlertDialog.Builder(this)
            .setTitle(if (isUpdate) getString(R.string.dialog_title_edit_tech_name) else getString(R.string.dialog_title_welcome))
            .setMessage(getString(R.string.dialog_msg_enter_tech_name))
            .setView(input)
            .setCancelable(isUpdate)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    saveTechnicianName(newName)
                    tvTechName.text = newName
                } else if (!isUpdate) {
                    checkTechnicianName()
                }
            }
            .apply {
                if (isUpdate) {
                    setNegativeButton(getString(R.string.btn_cancel), null)
                }
            }
            .show()
    }

    private fun getTechnicianName(): String? {
        return settingsRepository.technicianName
    }

    private fun saveTechnicianName(name: String) {
        settingsRepository.technicianName = name
    }

    private fun setupCompanyButton(button: MaterialButton, company: String) {
        button.setOnClickListener { handleCompanyClick(company) }
        button.setOnLongClickListener {
            lastSelectedCompany = company
            showCompanyOptionsMenu(company)
            true
        }
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
                        if (uri != null && isUriAccessible(uri)) {
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

    private fun showRenameDialog(company: String) {
        val uri = getCompanyFileUri(company) ?: return
        val currentFullName = getFileNameFromUri(uri)
        val currentName = currentFullName.substringBeforeLast('.')
        val extension = currentFullName.substringAfterLast('.', "")
        
        val input = EditText(this)
        input.setText(currentName)
        input.setSelectAllOnFocus(true)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_rename_file))
            .setMessage(getString(R.string.dialog_msg_rename_file))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_rename)) { _, _ ->
                val newBaseName = input.text.toString().trim()
                if (newBaseName.isNotEmpty() && newBaseName != currentName) {
                    val newDisplayName = if (extension.isNotEmpty()) "$newBaseName.$extension" else newBaseName
                    performPhysicalRename(company, uri, newDisplayName)
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun performPhysicalRename(company: String, uri: Uri, newName: String) {
        try {
            val newUri = DocumentsContract.renameDocument(contentResolver, uri, newName)
            if (newUri != null) {
                saveCompanyFileUri(company, newUri)
                if (currentFileUri == uri) {
                    openExcelFile(newUri)
                }
                Toast.makeText(this, getString(R.string.toast_file_renamed), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.toast_rename_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun handleShare() {
        val uri = currentFileUri ?: return
        saveExcelFile(silent = true) {
            Toast.makeText(this, getString(R.string.toast_file_ready_share), Toast.LENGTH_SHORT).show()
            try {
                val originalFullName = getFileNameFromUri(uri)
                val baseName = originalFullName.substringBeforeLast('.')
                
                // Il nome base deve essere sempre il nome dell'appalto
                var finalName = lastSelectedCompany ?: baseName

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

                // 1. Legge i materiali dal file del tecnico
                val techMaterials = adapter.getData().map { Pair(it.label, it.value) }

                // 2. Carica la lista master dagli assets (specifica per azienda o fallback)
                val masterList = AssetsHelper().loadMasterList(this, lastSelectedCompany)

                // 3. Esegue il merge (Tecnico + Master)
                val mergedList = MaterialMerger().merge(techMaterials, masterList)

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
        if (uri != null && isUriAccessible(uri)) {
            saveLastFileUri(uri)
            openExcelFile(uri)
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            showChoiceDialog(company)
        }
    }

    private fun isUriAccessible(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { it.close() }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun showChoiceDialog(company: String) {
        AlertDialog.Builder(this)
            .setTitle(company)
            .setMessage(getString(R.string.dialog_msg_config_company, company))
            .setPositiveButton(getString(R.string.btn_new_from_template)) { _, _ ->
                createFileFromTemplate("$company.xlsx")
            }
            .setNegativeButton(getString(R.string.btn_use_existing)) { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                selectFileLauncher.launch(intent)
            }
            .setNeutralButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun createFileFromTemplate(fileName: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_TITLE, fileName)
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
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_unsaved_changes))
            .setMessage(getString(R.string.dialog_msg_save_before_exit))
            .setNeutralButton(getString(R.string.btn_exit_without_saving)) { _, _ -> finish() }
            .setNegativeButton(getString(R.string.btn_no)) { dialog, _ -> dialog.dismiss() }
            .setPositiveButton(getString(R.string.btn_save_and_exit)) { _, _ -> saveExcelFile(silent = true) { finish() } }
            .show()
    }

    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_reset_file))
            .setMessage(getString(R.string.dialog_msg_reset_file))
            .setPositiveButton(getString(R.string.btn_reset)) { _, _ -> resetCurrentFile() }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun resetCurrentFile() {
        val uri = currentFileUri ?: return
        viewModel.resetExcelFile(uri, lastSelectedCompany)
        Toast.makeText(this@MainActivity, getString(R.string.toast_file_reset), Toast.LENGTH_SHORT).show()
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun loadLastFile() {
        lastSelectedCompany = settingsRepository.lastSelectedCompany
        val uriString = settingsRepository.lastFileUri

        uriString?.let {
            val uri = it.toUri()
            if (isUriAccessible(uri)) {
                openExcelFile(uri)
            } else {
                Toast.makeText(this, "Il file non esiste più nel percorso salvato.", Toast.LENGTH_LONG).show()
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

    private fun getCompanyFileUri(company: String): Uri? {
        val uriString = settingsRepository.getCompanyFileUri(company)
        return uriString?.toUri()
    }

    private fun openExcelFile(uri: Uri) {
        currentFileUri = uri
        val fileNameWithExt = getFileNameFromUri(uri)
        val fileName = fileNameWithExt.substringBeforeLast('.')
        tvCurrentFileName.text = lastSelectedCompany ?: getString(R.string.default_company_name)
        toolbar.title = fileName
        readExcelFile(uri)
        forceMediaStoreScan()
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = getString(R.string.default_file_name)
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (_: Exception) {}
        return name
    }

    private fun readExcelFile(uri: Uri) {
        viewModel.currentCompany = lastSelectedCompany
        lastSelectedCompany?.let { HistoryRepository(this).cleanOldSnapshots(it) }
        viewModel.loadExcelFile(uri, lastSelectedCompany)
    }

    private fun showSampleDialog() {
        viewModel.clearError()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_invalid_format))
            .setMessage(getString(R.string.dialog_msg_invalid_format))
            .setPositiveButton(getString(R.string.btn_use_template)) { _, _ -> createFileFromTemplate("${lastSelectedCompany ?: "Sample"}.xlsx") }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showDeleteConfirmation(position: Int) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_delete_row))
            .setMessage(getString(R.string.dialog_msg_delete_row))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                adapter.removeRow(position)
                Toast.makeText(this, getString(R.string.toast_row_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.btn_cancel)) { _, _ ->
                adapter.notifyItemChanged(position)
            }
            .setCancelable(false)
            .show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        saveMenuItem = menu?.findItem(R.id.action_save)
        saveMenuItem?.actionView?.setOnClickListener { onOptionsItemSelected(saveMenuItem!!) }
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
        
        val masterList = AssetsHelper().loadMasterList(this, lastSelectedCompany)
        val currentData = adapter.getData().map { Pair(it.label, it.value) }
        val mergedPairs = MaterialMerger().merge(currentData, masterList)
        val dataToSave = mergedPairs.map { ExcelRowData(it.first, it.second) }
        
        viewModel.saveExcelFile(uri, dataToSave) { success ->
            if (success) {
                if (!silent) Toast.makeText(this@MainActivity, getString(R.string.toast_file_saved), Toast.LENGTH_SHORT).show()
                saveLastFileUri(uri)
                onComplete?.invoke()
            } else {
                if (!silent) Toast.makeText(this@MainActivity, getString(R.string.toast_save_error), Toast.LENGTH_SHORT).show()
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
        val displayList = mutableListOf<HistoryItem>()
        
        for (i in 0 until historyList.size - 1) {
            val currentSnapshot = historyList[i]
            val previousSnapshot = historyList[i+1]
            val diff = calculateDiff(previousSnapshot.data, currentSnapshot.data, currentSnapshot.timestamp)
            if (diff.isNotEmpty()) {
                displayList.add(HistoryItem(diff, viewModel.undoStack.size - 1 - i, currentSnapshot.timestamp))
            }
        }

        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = object : RecyclerView.Adapter<HistoryViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history_row, parent, false)
                return HistoryViewHolder(v)
            }
            override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
                val item = displayList[position]
                holder.tvDiff.text = item.spannableDiff
                holder.itemView.setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Sei sicuro?")
                        .setMessage("Vuoi tornare alla modifica delle ${item.timestamp}? Le modifiche successive andranno perse.")
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
            override fun getItemCount() = displayList.size
        }
        
        dialog.setContentView(view)
        dialog.show()
    }

    private data class HistoryItem(val spannableDiff: SpannableStringBuilder, val stackIndex: Int, val timestamp: String)
    private class HistoryViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvDiff: TextView = v.findViewById(R.id.tvDiff)
    }

    private fun calculateDiff(old: List<ExcelRowData>, new: List<ExcelRowData>, timestamp: String): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        val blue = ContextCompat.getColor(this, R.color.gemini_accent_blue)
        val red = ContextCompat.getColor(this, R.color.gemini_destructive)
        val gray = Color.GRAY

        val newMap = new.associateBy { it.label }
        val oldMap = old.associateBy { it.label }

        val allLabels = (new.map { it.label } + old.map { it.label }).distinct()

        for (label in allLabels) {
            if (label.isEmpty()) continue
            val newValStr = newMap[label]?.value ?: ""
            val oldValStr = oldMap[label]?.value ?: ""
            
            if (newValStr != oldValStr) {
                val newVal = newValStr.toIntOrNull() ?: 0
                val oldVal = oldValStr.toIntOrNull() ?: 0
                val diff = newVal - oldVal
                
                if (diff != 0) {
                    if (ssb.isNotEmpty()) ssb.append("\n\n")
                    
                    val start = ssb.length
                    ssb.append(timestamp).append("  ")
                    ssb.setSpan(ForegroundColorSpan(gray), start, start + timestamp.length, 0)
                    
                    ssb.append(label).append("\n")
                    val sign = if (diff > 0) "+" else ""
                    val color = if (diff > 0) blue else red
                    val diffStr = "$sign$diff"
                    val startVal = ssb.length
                    ssb.append(diffStr)
                    ssb.setSpan(ForegroundColorSpan(color), startVal, ssb.length, 0)
                } else {
                    if (ssb.isNotEmpty()) ssb.append("\n\n")
                    val start = ssb.length
                    ssb.append(timestamp).append("  ")
                    ssb.setSpan(ForegroundColorSpan(gray), start, start + timestamp.length, 0)
                    ssb.append(label).append("\n${getString(R.string.text_modified)}")
                }
            }
        }
        return ssb
    }

    private fun forceMediaStoreScan() {
        MediaScannerConnection.scanFile(
            this,
            arrayOf(getExternalFilesDir(null)?.absolutePath ?: return),
            null,
            null
        )
    }
}
