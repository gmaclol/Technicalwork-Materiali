package com.technicalwork.materiali

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

    private lateinit var exchangeAdapter: ExchangeAdapter

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

        // Genera il QR con DeviceID + Company
        val qrData = JSONObject().apply {
            put("deviceId", localDeviceId)
            put("company", company)
        }.toString()

        val bitmap = generateQrBitmap(qrData, 600)
        if (bitmap != null) {
            ivQrCode.setImageBitmap(bitmap)
        } else {
            Toast.makeText(this, getString(R.string.exchange_qr_error), Toast.LENGTH_SHORT).show()
            finish()
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
            e.printStackTrace()
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
        } catch (e: Exception) {
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

            val inventory = withContext(Dispatchers.IO) {
                exchangeRepo.fetchRemoteInventory(remoteCompany, remoteDeviceId)
            }

            layoutLoading.visibility = View.GONE

            if (inventory == null || inventory.isEmpty()) {
                Toast.makeText(
                    this@ExchangeActivity,
                    getString(R.string.exchange_no_inventory),
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }

            showPaniere(inventory)
        }
    }

    private fun showPaniere(inventory: List<ExcelRowData>) {
        layoutQrDisplay.visibility = View.GONE
        layoutPaniere.visibility = View.VISIBLE

        toolbar.title = getString(R.string.exchange_title_basket)
        tvRemoteTechName.text = remoteTechName
        tvRemoteCompany.text = remoteCompany

        // Converti l'inventario in ExchangeRowData usando StockParser
        val separatorRegex = Regex("^::.*::$|^;;.*;;$")
        val exchangeRows = inventory
            .filter { !it.label.trim().matches(separatorRegex) && it.label.isNotBlank() }
            .map { row ->
                val stock = parser.parse(row.label, row.value)
                ExchangeRowData(
                    label = row.label,
                    availableFree = stock.free,
                    availableUsed = stock.used,
                    hasUsedPart = parser.hasUsedPart(row.value)
                )
            }
            .filter { it.availableFree > 0 || it.availableUsed > 0 }

        exchangeAdapter = ExchangeAdapter(exchangeRows.toMutableList()) {
            btnConfirmExchange.isEnabled = exchangeAdapter.hasSelection()
        }

        rvExchange.layoutManager = LinearLayoutManager(this)
        rvExchange.adapter = exchangeAdapter

        // Toggle Direzione
        toggleDirection.check(R.id.btnTakeFromA)
        toggleDirection.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentDirection = when (checkedId) {
                    R.id.btnTakeFromA -> ExchangeDirection.B_TAKES_FROM_A
                    R.id.btnGiveToA -> ExchangeDirection.B_GIVES_TO_A
                    else -> ExchangeDirection.B_TAKES_FROM_A
                }
                exchangeAdapter.isTakingFromA = currentDirection == ExchangeDirection.B_TAKES_FROM_A
            }
        }

        // Bottone Conferma
        btnConfirmExchange.setOnClickListener {
            showConfirmDialog()
        }
    }

    private fun showConfirmDialog() {
        val selectedItems = exchangeAdapter.getSelectedItems()
        if (selectedItems.isEmpty()) return

        val directionText = if (currentDirection == ExchangeDirection.B_TAKES_FROM_A) {
            getString(R.string.exchange_confirm_take, remoteTechName)
        } else {
            getString(R.string.exchange_confirm_give, remoteTechName)
        }

        val itemsSummary = selectedItems.joinToString("\n") { item ->
            val parts = mutableListOf<String>()
            if (item.qtyFree > 0) parts.add("${item.qtyFree} liberi")
            if (item.qtyUsed > 0) parts.add("${item.qtyUsed} sparati")
            "• ${item.label}: ${parts.joinToString(", ")}"
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.exchange_confirm_title))
            .setMessage("$directionText\n\n$itemsSummary")
            .setPositiveButton(getString(R.string.exchange_confirm)) { _, _ ->
                executeExchange(selectedItems)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun executeExchange(selectedItems: List<ExchangeItem>) {
        layoutLoading.visibility = View.VISIBLE

        lifecycleScope.launch {
            // Coordinate GPS
            val (lat, lng) = getLastLocation()

            val success = withContext(Dispatchers.IO) {
                exchangeRepo.executeExchange(
                    fromDeviceId = localDeviceId,
                    toDeviceId = remoteDeviceId,
                    fromTechName = localTechName,
                    toTechName = remoteTechName,
                    company = remoteCompany,
                    items = selectedItems,
                    direction = currentDirection,
                    lat = lat,
                    lng = lng
                )
            }

            if (success) {
                // Aggiorna l'inventario locale di B
                updateLocalInventory(selectedItems)

                layoutLoading.visibility = View.GONE
                Toast.makeText(
                    this@ExchangeActivity,
                    getString(R.string.exchange_success),
                    Toast.LENGTH_LONG
                ).show()

                setResult(RESULT_OK)
                finish()
            } else {
                layoutLoading.visibility = View.GONE
                Toast.makeText(
                    this@ExchangeActivity,
                    getString(R.string.exchange_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Aggiorna l'inventario locale di B (chi ha scansionato).
     * Se B prende da A → aggiunge ai propri materiali.
     * Se B dà ad A → sottrae dai propri materiali.
     */
    private suspend fun updateLocalInventory(selectedItems: List<ExchangeItem>) {
        // Per l'aggiornamento locale, il tecnico B deve avere aperto lo stesso appalto.
        // Leggiamo il file corrente usando il company e il deviceId locale.
        val company = remoteCompany
        val localFileUri = settingsRepository.getCompanyFileUri(company) ?: return

        val uri = android.net.Uri.parse(localFileUri)
        val excelRepo = ExcelRepository(this)
        val result = excelRepo.readExcelFile(uri, company)
        if (result.isFailure) return

        val localData = result.getOrNull()?.toMutableList() ?: return

        // Applica le modifiche
        for (item in selectedItems) {
            val index = localData.indexOfFirst { it.label == item.label }
            if (index >= 0) {
                val stock = parser.parse(localData[index].label, localData[index].value)
                val newStock = when (currentDirection) {
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
            else if (currentDirection == ExchangeDirection.B_TAKES_FROM_A) {
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
                this@ExchangeActivity,
                company,
                localTechName,
                localData,
                lat, lng,
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
        } catch (e: Exception) {
            null to null
        }
    }
}
