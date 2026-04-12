package com.technicalwork.materiali

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Activity per lo scambio materiale tra due tecnici.
 *
 * Modalità GENERATORE (Tecnico A): Mostra il QR Code con il proprio DeviceID e appalto.
 * Modalità SCANNER (Tecnico B): Scansiona il QR, carica l'inventario di A,
 *   permette la selezione dei materiali e conferma lo scambio.
 */
class ExchangeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "exchange_mode"
        const val MODE_GENERATE = "generate"
        const val MODE_SCAN = "scan"
    }

    private lateinit var toolbar: Toolbar
    private lateinit var layoutQrDisplay: View
    private lateinit var layoutPaniere: View
    private lateinit var layoutLoading: View

    // QR Display
    private lateinit var ivQrCode: ImageView
    private lateinit var tvQrSubtitle: TextView
    private lateinit var tvQrStatus: TextView

    // Paniere
    private lateinit var tvRemoteTechName: TextView
    private lateinit var tvRemoteCompany: TextView
    private lateinit var toggleDirection: MaterialButtonToggleGroup
    private lateinit var btnTakeFromA: MaterialButton
    private lateinit var btnGiveToA: MaterialButton
    private lateinit var rvExchange: RecyclerView
    private lateinit var btnConfirmExchange: MaterialButton

    private lateinit var exchangeRepo: ExchangeRepository
    private lateinit var settingsRepository: SettingsRepository
    private val parser = StockParser()

    private var remoteDeviceId: String = ""
    private var remoteCompany: String = ""
    private var remoteTechName: String = ""
    private var localDeviceId: String = ""
    private var localTechName: String = ""
    private var currentDirection = ExchangeDirection.B_TAKES_FROM_A

    private lateinit var remoteAdapter: ExchangeAdapter
    private lateinit var localAdapter: ExchangeAdapter

    // Scanner QR launcher
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScannedQr(result.contents)
        } else {
            Toast.makeText(this, getString(R.string.exchange_scan_cancelled), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exchange)

        exchangeRepo = ExchangeRepository()
        settingsRepository = SettingsRepository(this)
        @SuppressLint("HardwareIds")
        localDeviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        localTechName = settingsRepository.technicianName ?: "Sconosciuto"

        initViews()
        setupToolbar()

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_GENERATE
        when (mode) {
            MODE_GENERATE -> showQrGenerator()
            MODE_SCAN -> startQrScanner()
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarExchange)
        layoutQrDisplay = findViewById(R.id.layoutQrDisplay)
        layoutPaniere = findViewById(R.id.layoutPaniere)
        layoutLoading = findViewById(R.id.layoutLoading)

        ivQrCode = findViewById(R.id.ivQrCode)
        tvQrSubtitle = findViewById(R.id.tvQrSubtitle)
        tvQrStatus = findViewById(R.id.tvQrStatus)

        tvRemoteTechName = findViewById(R.id.tvRemoteTechName)
        tvRemoteCompany = findViewById(R.id.tvRemoteCompany)
        toggleDirection = findViewById(R.id.toggleDirection)
        btnTakeFromA = findViewById(R.id.btnTakeFromA)
        btnGiveToA = findViewById(R.id.btnGiveToA)
        rvExchange = findViewById(R.id.rvExchange)
        btnConfirmExchange = findViewById(R.id.btnConfirmExchange)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
    }

    // ==================== MODALITÀ GENERATORE (Tecnico A) ====================

    private fun showQrGenerator() {
        layoutQrDisplay.visibility = View.VISIBLE
        layoutPaniere.visibility = View.GONE

        // Recupera l'appalto attualmente aperto
        val company = settingsRepository.lastSelectedCompany ?: ""
        if (company.isEmpty() || company == "Consumo") {
            Toast.makeText(this, getString(R.string.exchange_no_company), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        toolbar.title = getString(R.string.exchange_title_generate)
        tvQrSubtitle.text = getString(R.string.exchange_show_qr_company, company)

        val excelRepo = ExcelRepository(this)
        val fileStorageManager = FileStorageManager(this)

        lifecycleScope.launch {
            val uriString = settingsRepository.getCompanyFileUri(company)
            if (uriString != null) {
                val uri = uriString.toUri()
                if (fileStorageManager.isUriAccessible(uri)) {
                    val result = withContext(Dispatchers.IO) { excelRepo.readExcelFile(uri, company) }
                    val localData = result.getOrNull()
                    if (localData != null) {
                        val (lat, lng) = getLastLocation()
                        withContext(Dispatchers.IO) {
                            FirebaseRepository().syncToFirestore(
                                context = this@ExchangeActivity,
                                company = company,
                                technicianName = localTechName,
                                materials = localData,
                                lat = lat,
                                lng = lng,
                                deviceId = localDeviceId
                            )
                        }
                    }
                }
            }

            // Genera il QR con DeviceID + Company
            val qrData = JSONObject().apply {
                put("deviceId", localDeviceId)
                put("company", company)
            }.toString()

            val bitmap = generateQrBitmap(qrData, 600)
            if (bitmap != null) {
                ivQrCode.setImageBitmap(bitmap)
            } else {
                Toast.makeText(this@ExchangeActivity, getString(R.string.exchange_qr_error), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun generateQrBitmap(content: String, size: Int): Bitmap? {
        return try {
            val bitMatrix: BitMatrix = MultiFormatWriter().encode(
                content, BarcodeFormat.QR_CODE, size, size
            )
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    // ==================== MODALITÀ SCANNER (Tecnico B) ====================

    private fun startQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.exchange_scan_prompt))
            setCameraId(0)
            setBeepEnabled(false)
            setOrientationLocked(true)
            setCaptureActivity(PortraitCaptureActivity::class.java)
        }
        scanLauncher.launch(options)
    }

    private fun handleScannedQr(contents: String) {
        try {
            val json = JSONObject(contents)
            remoteDeviceId = json.getString("deviceId")
            remoteCompany = json.getString("company")

            if (remoteDeviceId == localDeviceId) {
                Toast.makeText(this, getString(R.string.exchange_self_error), Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            loadRemoteInventory()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.exchange_invalid_qr), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadRemoteInventory() {
        layoutLoading.visibility = View.VISIBLE
        toolbar.title = getString(R.string.exchange_title_loading)

        lifecycleScope.launch {
            // Carica nome tecnico e inventario in parallelo
            remoteTechName = withContext(Dispatchers.IO) {
                exchangeRepo.fetchRemoteTechName(remoteCompany, remoteDeviceId) ?: "Tecnico"
            }

            var remoteInventory = withContext(Dispatchers.IO) {
                exchangeRepo.fetchRemoteInventory(remoteCompany, remoteDeviceId)
            }

            if (remoteInventory == null || remoteInventory.isEmpty()) {
                remoteInventory = com.technicalwork.materiali.AssetsHelper()
                    .loadMasterList(this@ExchangeActivity, remoteCompany)
                    .map { ExcelRowData(it, "") }
            }

            var localInventory: List<ExcelRowData>? = null
            val excelRepo = ExcelRepository(this@ExchangeActivity)
            val uriString = settingsRepository.getCompanyFileUri(remoteCompany)
            if (uriString != null) {
                val uri = uriString.toUri()
                if (FileStorageManager(this@ExchangeActivity).isUriAccessible(uri)) {
                    localInventory = withContext(Dispatchers.IO) { excelRepo.readExcelFile(uri, remoteCompany).getOrNull() }
                }
            }
            if (localInventory == null || localInventory.isEmpty()) {
                localInventory = com.technicalwork.materiali.AssetsHelper()
                    .loadMasterList(this@ExchangeActivity, remoteCompany)
                    .map { ExcelRowData(it, "") }
            }

            layoutLoading.visibility = View.GONE

            if (remoteInventory.isEmpty() && localInventory.isEmpty()) {
                Toast.makeText(
                    this@ExchangeActivity,
                    getString(R.string.exchange_no_inventory),
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }

            showPaniere(remoteInventory, localInventory)
        }
    }

    private fun updateConfirmButton() {
        if (::remoteAdapter.isInitialized && ::localAdapter.isInitialized) {
            btnConfirmExchange.isEnabled = remoteAdapter.hasSelection() || localAdapter.hasSelection()
        }
    }

    private fun showPaniere(remoteInventory: List<ExcelRowData>, localInventory: List<ExcelRowData>) {
        layoutQrDisplay.visibility = View.GONE
        layoutPaniere.visibility = View.VISIBLE

        toolbar.title = getString(R.string.exchange_title_basket)
        tvRemoteTechName.text = remoteTechName
        tvRemoteCompany.text = remoteCompany

        // Converti l'inventario in ExchangeRowData usando StockParser
        val separatorRegex = Regex("^::.*::$|^;;.*;;$")
        
        val remoteExchangeRows = remoteInventory
            .filter { !it.label.trim().matches(separatorRegex) && it.label.isNotBlank() }
            .map { row ->
                val stock = parser.parse(row.label, row.value)
                ExchangeRowData(row.label, stock.free, stock.used, parser.hasUsedPart(row.value))
            }
            
        val localExchangeRows = localInventory
            .filter { !it.label.trim().matches(separatorRegex) && it.label.isNotBlank() }
            .map { row ->
                val stock = parser.parse(row.label, row.value)
                ExchangeRowData(row.label, stock.free, stock.used, parser.hasUsedPart(row.value))
            }

        remoteAdapter = ExchangeAdapter(remoteExchangeRows.toMutableList()) { updateConfirmButton() }
        remoteAdapter.isTakingFromA = true

        localAdapter = ExchangeAdapter(localExchangeRows.toMutableList()) { updateConfirmButton() }
        localAdapter.isTakingFromA = false

        rvExchange.layoutManager = LinearLayoutManager(this)
        rvExchange.adapter = remoteAdapter

        // Toggle Direzione
        toggleDirection.check(R.id.btnTakeFromA)
        toggleDirection.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnTakeFromA -> {
                        currentDirection = ExchangeDirection.B_TAKES_FROM_A
                        rvExchange.adapter = remoteAdapter
                    }
                    R.id.btnGiveToA -> {
                        currentDirection = ExchangeDirection.B_GIVES_TO_A
                        rvExchange.adapter = localAdapter
                    }
                }
            }
        }

        // Bottone Conferma
        btnConfirmExchange.setOnClickListener {
            showConfirmDialog()
        }
    }

    private fun showConfirmDialog() {
        val takenItems = remoteAdapter.getSelectedItems()
        val givenItems = localAdapter.getSelectedItems()
        
        if (takenItems.isEmpty() && givenItems.isEmpty()) return

        val partsText = mutableListOf<String>()
        if (takenItems.isNotEmpty()) partsText.add(getString(R.string.exchange_confirm_take, remoteTechName))
        if (givenItems.isNotEmpty()) partsText.add(getString(R.string.exchange_confirm_give, remoteTechName))
        val directionText = partsText.joinToString("\n\n")

        val itemsSummary = mutableListOf<String>()
        if (takenItems.isNotEmpty()) {
            itemsSummary.add("--- PRENDI ---")
            itemsSummary.addAll(takenItems.map { item ->
                val p = mutableListOf<String>()
                if (item.qtyFree > 0) p.add("${item.qtyFree} liberi")
                if (item.qtyUsed > 0) p.add("${item.qtyUsed} sparati")
                "• ${item.label}: ${p.joinToString(", ")}"
            })
        }
        if (givenItems.isNotEmpty()) {
            itemsSummary.add("--- DAI ---")
            itemsSummary.addAll(givenItems.map { item ->
                val p = mutableListOf<String>()
                if (item.qtyFree > 0) p.add("${item.qtyFree} liberi")
                if (item.qtyUsed > 0) p.add("${item.qtyUsed} sparati")
                "• ${item.label}: ${p.joinToString(", ")}"
            })
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.exchange_confirm_title))
            .setMessage(directionText + "\n\n" + itemsSummary.joinToString("\n"))
            .setPositiveButton(getString(R.string.exchange_confirm)) { _, _ ->
                executeCombinedExchange(takenItems, givenItems)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun executeCombinedExchange(takenItems: List<ExchangeItem>, givenItems: List<ExchangeItem>) {
        layoutLoading.visibility = View.VISIBLE

        lifecycleScope.launch {
            val (lat, lng) = getLastLocation()
            var allSuccess = true

            if (takenItems.isNotEmpty()) {
                val successTake = withContext(Dispatchers.IO) {
                    exchangeRepo.executeExchange(
                        fromDeviceId = localDeviceId,
                        toDeviceId = remoteDeviceId,
                        fromTechName = localTechName,
                        toTechName = remoteTechName,
                        company = remoteCompany,
                        items = takenItems,
                        direction = ExchangeDirection.B_TAKES_FROM_A,
                        lat = lat,
                        lng = lng
                    )
                }
                if (successTake) updateLocalInventory(takenItems, ExchangeDirection.B_TAKES_FROM_A)
                allSuccess = allSuccess && successTake
            }

            if (givenItems.isNotEmpty()) {
                val successGive = withContext(Dispatchers.IO) {
                    exchangeRepo.executeExchange(
                        fromDeviceId = localDeviceId,
                        toDeviceId = remoteDeviceId,
                        fromTechName = localTechName,
                        toTechName = remoteTechName,
                        company = remoteCompany,
                        items = givenItems,
                        direction = ExchangeDirection.B_GIVES_TO_A,
                        lat = lat,
                        lng = lng
                    )
                }
                if (successGive) updateLocalInventory(givenItems, ExchangeDirection.B_GIVES_TO_A)
                allSuccess = allSuccess && successGive
            }

            layoutLoading.visibility = View.GONE
            if (allSuccess) {
                Toast.makeText(this@ExchangeActivity, getString(R.string.exchange_success), Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            } else {
                Toast.makeText(this@ExchangeActivity, getString(R.string.exchange_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Aggiorna l'inventario locale di B (chi ha scansionato).
     * Se B prende da A → aggiunge ai propri materiali.
     * Se B dà ad A → sottrae dai propri materiali.
     */
    private suspend fun updateLocalInventory(selectedItems: List<ExchangeItem>, appliedDirection: ExchangeDirection) {
        val company = remoteCompany
        val localFileUri = settingsRepository.getCompanyFileUri(company) ?: return
        val uri = localFileUri.toUri()
        val excelRepo = ExcelRepository(this)
        val result = excelRepo.readExcelFile(uri, company)
        if (result.isFailure) return

        val localData = result.getOrNull()?.toMutableList() ?: return

        for (item in selectedItems) {
            val index = localData.indexOfFirst { it.label == item.label }
            if (index >= 0) {
                val stock = parser.parse(localData[index].label, localData[index].value)
                val newStock = when (appliedDirection) {
                    ExchangeDirection.B_TAKES_FROM_A -> {
                        stock.copy(
                            free = stock.free + item.qtyFree,
                            used = stock.used + item.qtyUsed
                        )
                    }
                    ExchangeDirection.B_GIVES_TO_A -> {
                        stock.copy(
                            free = (stock.free - item.qtyFree).coerceAtLeast(0),
                            used = (stock.used - item.qtyUsed).coerceAtLeast(0)
                        )
                    }
                }
                localData[index] = ExcelRowData(localData[index].label, parser.recompose(newStock))
            }
            // Se il materiale non esiste nella lista di B e B lo sta prendendo, lo aggiunge
            else if (appliedDirection == ExchangeDirection.B_TAKES_FROM_A) {
                val newValue = if (item.qtyUsed > 0) {
                    "${item.qtyFree} + ${item.qtyUsed} sparat"
                } else if (item.qtyFree > 0) {
                    item.qtyFree.toString()
                } else {
                    ""
                }
                if (newValue.isNotEmpty()) {
                    localData.add(ExcelRowData(item.label, newValue))
                }
            }
        }

        // Salva il file Excel locale
        withContext(Dispatchers.IO) {
            excelRepo.saveExcelFile(uri, localData)
        }

        // Sync Firestore del proprio inventario
        val (lat, lng) = getLastLocation()
        withContext(Dispatchers.IO) {
            FirebaseRepository().syncToFirestore(
                context = this@ExchangeActivity,
                company = company,
                technicianName = localTechName,
                materials = localData,
                lat = lat,
                lng = lng,
                deviceId = localDeviceId
            )
        }
    }

    private fun getLastLocation(): Pair<Double?, Double?> {
        return try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
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
}
