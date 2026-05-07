package com.technicalwork.materiali

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.google.firebase.firestore.ListenerRegistration

class GeoNavActivity : AppCompatActivity() {

    private lateinit var rvGeoNav: RecyclerView
    private lateinit var pbGeoNav: ProgressBar
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvCustomTitle: TextView
    private lateinit var etSearch: EditText
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var configManager: ConfigManager
    private lateinit var tvTechName: TextView
    
    private var fullData: JSONObject? = null
    private val displayList = mutableListOf<TreeItem>()
    private val treeStateList = mutableListOf<TreeItem>()
    private val adapter = GeoNavAdapter()
    private var isSearching = false
    private var dashboardListener: ListenerRegistration? = null

    enum class TreeType { REGION, CAT_MACRO, CAT_COMUNI, MACROZONE, COMUNE_IN_MACRO, COMUNE_DIRECT, PFS }

    class TreeItem(
        val text: String,
        val level: Int,
        val type: TreeType,
        var isExpanded: Boolean = false,
        var hasChildren: Boolean = true,
        var parentRegion: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_geo_nav)

        toolbar = findViewById(R.id.toolbar)
        rvGeoNav = findViewById(R.id.rvGeoNav)
        pbGeoNav = findViewById(R.id.pbGeoNav)
        tvCustomTitle = findViewById(R.id.customToolbarTitle)
        etSearch = findViewById(R.id.etSearch)
        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)

        settingsRepository = SettingsRepository(this)
        configManager = ConfigManager(this)

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
            finishAffinity()
        }

        tvTechName = findViewById(R.id.tvTechName)
        val layoutTechName = findViewById<View>(R.id.layoutTechName)
        
        val currentName = settingsRepository.technicianName
        tvTechName.text = currentName ?: "Non impostato"
        
        layoutTechName?.setOnClickListener {
            showTechnicianNameDialog()
        }

        setupDynamicDrawer()

        sharedPrefs = getSharedPreferences("GeoNavPrefs", Context.MODE_PRIVATE)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        tvCustomTitle.text = "Aggiungi Aree / Comuni"
        
        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        findViewById<android.widget.ImageView>(R.id.btnFilterRegions)?.setOnClickListener {
            showFilterDialog()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                performSearch(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        rvGeoNav.layoutManager = LinearLayoutManager(this)
        rvGeoNav.adapter = adapter

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else if (!handleBack()) {
                    finish()
                }
            }
        })

        loadJsonData()
        setupDashboardListener()
    }

    private fun setupDashboardListener() {
        dashboardListener = FavoriteManager.attachDashboardListener(
            context = this,
            settingsRepo = settingsRepository
        ) { _, newFavorites ->
            if (newFavorites != null) {
                runOnUiThread {
                    setupDynamicDrawer()
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dashboardListener?.remove()
    }

    private fun handleBack(): Boolean {
        for (i in displayList.indices.reversed()) {
            if (displayList[i].isExpanded) {
                collapseItem(displayList[i], i)
                return true
            }
        }
        return false
    }

    private fun showFilterDialog() {
        val availableRegions = mutableListOf<String>()
        for (region in configManager.ITALIAN_REGIONS) {
            val cachedFile = java.io.File(filesDir, "$region.json")
            if (cachedFile.exists()) {
                availableRegions.add(region)
            } else {
                try {
                    assets.open("$region.json").use { }
                    availableRegions.add(region)
                } catch (e: Exception) {
                    // File non disponibile
                }
            }
        }

        val regions = availableRegions.toTypedArray()
        val checkedItems = BooleanArray(regions.size) { i ->
            sharedPrefs.getBoolean("filter_${regions[i]}", regions[i] == "Piemonte")
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Filtra Regioni")
            .setMultiChoiceItems(regions, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Salva") { _, _ ->
                val editor = sharedPrefs.edit()
                for (i in regions.indices) {
                    editor.putBoolean("filter_${regions[i]}", checkedItems[i])
                }
                editor.apply()
                
                // Ricarica i dati con i nuovi filtri
                displayList.clear()
                treeStateList.clear()
                adapter.notifyDataSetChanged()
                loadJsonData()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun loadJsonData() {
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { pbGeoNav.visibility = View.VISIBLE }
            try {
                // Forza scaricamento aggiornamenti da GitHub per tutte le regioni
                configManager.fetchRemoteRegionsJson()

                fullData = org.json.JSONObject()
                val regionsAdded = mutableListOf<String>()

                for (region in configManager.ITALIAN_REGIONS) {
                    if (!sharedPrefs.getBoolean("filter_$region", region == "Piemonte")) continue

                    val cachedFile = java.io.File(filesDir, "$region.json")
                    var jsonString: String? = null

                    if (cachedFile.exists()) {
                        jsonString = cachedFile.inputStream().bufferedReader().use { it.readText() }
                    } else {
                        try {
                            jsonString = assets.open("$region.json").bufferedReader().use { it.readText() }
                        } catch (e: Exception) {
                            // File non presente negli assets, continuiamo
                        }
                    }

                    if (jsonString != null) {
                        try {
                            val regionJson = org.json.JSONObject(jsonString)
                            val regionObj = regionJson.optJSONObject(region)
                            if (regionObj != null) {
                                fullData?.put(region, regionObj)
                                regionsAdded.add(region)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    regionsAdded.forEachIndexed { index, regionName ->
                        displayList.add(TreeItem(regionName, 0, TreeType.REGION, parentRegion = regionName))
                        adapter.notifyItemInserted(index)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GeoNavActivity, "Errore caricamento dati", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) { pbGeoNav.visibility = View.GONE }
            }
        }
    }

    private fun performSearch(query: String) {
        if (query.isEmpty()) {
            isSearching = false
            displayList.clear()
            displayList.addAll(treeStateList)
            adapter.notifyDataSetChanged()
            return
        }
        
        if (!isSearching) {
            treeStateList.clear()
            treeStateList.addAll(displayList)
            isSearching = true
        }

        val filtered = mutableListOf<TreeItem>()
        fullData?.let { data ->
            data.keys().forEach { regionName ->
                val regionObj = data.optJSONObject(regionName) ?: return@forEach
                val comuni = regionObj.optJSONObject("Comuni")
                comuni?.keys()?.forEach { comune ->
                    val arr = comuni.optJSONArray(comune)
                    if (comune.contains(query, ignoreCase = true)) {
                        filtered.add(TreeItem(comune, 0, TreeType.COMUNE_DIRECT, hasChildren = true, parentRegion = regionName))
                    } else if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val pfsStr = arr.getString(i)
                            if (pfsStr.contains(query, ignoreCase = true)) {
                                filtered.add(TreeItem(pfsStr, 0, TreeType.PFS, hasChildren = false, parentRegion = regionName))
                            }
                        }
                    }
                }
                val macrozone = regionObj.optJSONObject("Macrozone")
                macrozone?.keys()?.forEach { macro ->
                    val arr = macrozone.optJSONArray(macro)
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val entry = arr.getString(i)
                            if (entry.startsWith("<") && entry.endsWith(">")) {
                                val clean = entry.removeSurrounding("<", ">")
                                if (clean.contains(query, ignoreCase = true)) {
                                    filtered.add(TreeItem(macro, 0, TreeType.MACROZONE, hasChildren = false, parentRegion = regionName))
                                }
                            } else {
                                if (entry.contains(query, ignoreCase = true)) {
                                    filtered.add(TreeItem(entry, 0, TreeType.PFS, hasChildren = false, parentRegion = regionName))
                                }
                            }
                        }
                    }
                }
            }
        }
        
        displayList.clear()
        displayList.addAll(filtered.distinctBy { it.text }.sortedBy { 
            (if (it.type == TreeType.PFS) "1_" else "0_") + it.text.removeSurrounding("<", ">")
        })
        adapter.notifyDataSetChanged()
    }
    private fun openComuneInPfsActivity(item: TreeItem, position: Int) {
        val regionObj = fullData?.optJSONObject(item.parentRegion)
        val pfsLines = mutableListOf<String>()
        var title = ""

        if (item.type == TreeType.COMUNE_DIRECT) {
            title = item.text
            val array = regionObj?.optJSONObject("Comuni")?.optJSONArray(item.text)
            array?.let {
                for (i in 0 until it.length()) pfsLines.add(it.getString(i))
            }
        } else if (item.type == TreeType.MACROZONE) {
            val macroName = item.text
            val array = regionObj?.optJSONObject("Macrozone")?.optJSONArray(macroName)
            array?.let {
                for (i in 0 until it.length()) {
                    val entry = it.getString(i)
                    if (entry.startsWith("<") && entry.endsWith(">")) {
                        title = "$macroName (${entry.removeSurrounding("<", ">")})"
                    } else {
                        pfsLines.add(entry)
                    }
                }
            }
            if (title.isEmpty()) title = macroName
        }

        if (pfsLines.isNotEmpty()) {
            val intent = Intent(this, PfsActivity::class.java).apply {
                putExtra("SELECTED_PFS_CONTENT", pfsLines.joinToString("\n"))
                putExtra("SELECTED_TITLE", "PFS - $title")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Nessun PFS trovato per questo comune", Toast.LENGTH_SHORT).show()
        }
    }

    private fun expandItem(item: TreeItem, position: Int) {
        item.isExpanded = true
        val children = mutableListOf<TreeItem>()
        val nextLevel = item.level + 1
        
        val regionObj = fullData?.optJSONObject(item.parentRegion)
        val pRegion = item.parentRegion
        
        when (item.type) {
            TreeType.REGION -> {
                children.add(TreeItem("Macrozone", nextLevel, TreeType.CAT_MACRO, parentRegion = pRegion))
                children.add(TreeItem("Comuni", nextLevel, TreeType.CAT_COMUNI, parentRegion = pRegion))
            }
            TreeType.CAT_MACRO -> {
                val obj = regionObj?.optJSONObject("Macrozone")
                obj?.keys()?.let { keys ->
                    while (keys.hasNext()) {
                        children.add(TreeItem(keys.next(), nextLevel, TreeType.MACROZONE, hasChildren = false, parentRegion = pRegion))
                    }
                }
                children.sortBy { it.text }
            }
            TreeType.CAT_COMUNI -> {
                val obj = regionObj?.optJSONObject("Comuni")
                obj?.keys()?.let { keys ->
                    while (keys.hasNext()) {
                        children.add(TreeItem(keys.next(), nextLevel, TreeType.COMUNE_DIRECT, parentRegion = pRegion))
                    }
                }
                children.sortBy { it.text }
            }
            TreeType.COMUNE_DIRECT -> {
                val array = regionObj?.optJSONObject("Comuni")?.optJSONArray(item.text)
                array?.let {
                    for (i in 0 until it.length()) {
                        children.add(TreeItem(it.getString(i), nextLevel, TreeType.PFS, hasChildren = false, parentRegion = pRegion))
                    }
                }
            }
            TreeType.PFS -> {}
            TreeType.MACROZONE -> {}
            TreeType.COMUNE_IN_MACRO -> {}
        }
        
        if (children.isEmpty()) {
            item.hasChildren = false
            item.isExpanded = false
            adapter.notifyItemChanged(position)
            return
        }

        displayList.addAll(position + 1, children)
        adapter.notifyItemRangeInserted(position + 1, children.size)
        adapter.notifyItemChanged(position)
        
        if (children.size > 20) {
            (rvGeoNav.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0)
        }
    }

    private fun collapseItem(item: TreeItem, position: Int) {
        item.isExpanded = false
        var count = 0
        for (i in position + 1 until displayList.size) {
            if (displayList[i].level > item.level) count++ else break
        }
        if (count > 0) {
            for (i in 0 until count) displayList.removeAt(position + 1)
            adapter.notifyItemRangeRemoved(position + 1, count)
        }
        adapter.notifyItemChanged(position)
    }

    private fun openPfsMap(pfsStr: String) {
        var address = ""
        if (pfsStr.contains("::::")) {
            val parts = pfsStr.split("::::")
            address = if (parts.size > 1) parts[1].trim() else ""
        } else if (pfsStr.contains("::")) {
            val parts = pfsStr.split("::")
            address = if (parts.size > 1) parts[1].trim() else ""
        }

        if (address.isNotBlank()) {
            val bracketRegex = Regex("\\[(.*?)\\]")
            val match = bracketRegex.find(address)
            val displayAddress = address.replace(bracketRegex, "").trim()

            val geoPrefix = "geo:0,0"
            val mapsQuery = if (match != null) match.groupValues[1].trim() else displayAddress

            val uri = android.net.Uri.parse("$geoPrefix?q=${android.net.Uri.encode(mapsQuery)}")
            val mapIntent = Intent(Intent.ACTION_VIEW, uri)
            mapIntent.setPackage("com.google.android.apps.maps")
            if (mapIntent.resolveActivity(packageManager) != null) {
                startActivity(mapIntent)
            } else {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(fallbackIntent)
            }
        } else {
            Toast.makeText(this, "Indirizzo non disponibile per questo PFS", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTechnicianNameDialog() {
        val input = EditText(this)
        val currentName = settingsRepository.technicianName
        if (currentName != null) {
            input.setText(currentName)
            input.setSelectAllOnFocus(true)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_edit_tech_name))
            .setMessage(getString(R.string.dialog_msg_enter_tech_name))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    settingsRepository.technicianName = newName
                    tvTechName.text = newName
                    
                    @android.annotation.SuppressLint("HardwareIds")
                    val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
                    lifecycleScope.launch(Dispatchers.IO) {
                        FirebaseRepository().updateTechnicianName(deviceId, newName, configManager.getCompanies())
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun setupDynamicDrawer() {
        val container = findViewById<android.widget.LinearLayout>(R.id.llPfsAreasContainer) ?: return
        container.removeAllViews()

        val areas = configManager.getPfsAreas().toMutableList()
        val favPrefs = getSharedPreferences("GeoNavPrefs", Context.MODE_PRIVATE)
        val allPrefs = favPrefs.all
        for ((key, value) in allPrefs) {
            if (key.startsWith("fav_") && value == true) {
                val favName = key.removePrefix("fav_")
                if (!areas.contains(favName)) {
                    areas.add(favName)
                }
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
                setOnClickListener {
                    val pfsPrefs = getSharedPreferences("pfs_prefs", Context.MODE_PRIVATE)
                    pfsPrefs.edit().putString("pfs_last_area", area).apply()
                    val intent = Intent(this@GeoNavActivity, PfsActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(intent)
                    finish()
                }
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
                    FavoriteManager.setFavorite(this@GeoNavActivity, area, false)
                    setupDynamicDrawer()
                    adapter.notifyDataSetChanged()
                    FavoriteManager.persistFavoritesToFirebase(
                        this@GeoNavActivity,
                        SettingsRepository(this@GeoNavActivity),
                        lifecycleScope
                    )
                }
            }

            row.addView(button)
            row.addView(starBtn)
            container.addView(row)
        }

        findViewById<android.view.View>(R.id.btnGeoNav)?.setOnClickListener {
            drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
        }
    }

    inner class GeoNavAdapter : RecyclerView.Adapter<GeoNavAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_geo_nav, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = displayList[position]
            
            // Indentazione
            val indent = item.level * 24
            holder.llContainer.setPadding(indent + 32, 24, 32, 24)

            // Gestione indicatori base
            if (item.hasChildren && item.type != TreeType.PFS && item.type != TreeType.COMUNE_DIRECT && item.type != TreeType.MACROZONE) {
                holder.ivExpandIndicator.visibility = View.VISIBLE
                holder.ivExpandIndicator.rotation = if (item.isExpanded) 90f else 0f
            } else {
                holder.ivExpandIndicator.visibility = View.INVISIBLE
            }

            // Testo e Colori
            holder.tvNameMarquee.visibility = View.GONE
            holder.tvNameMarquee.isSelected = false
            
            val cleanName = item.text.removeSurrounding("<", ">")
            
            if (item.type == TreeType.PFS) {
                var name = item.text
                var address = ""
                if (name.contains("::::")) {
                    val parts = name.split("::::")
                    name = parts[0].trim()
                    address = if (parts.size > 1) parts[1].trim() else ""
                } else if (name.contains("::")) {
                    val parts = name.split("::")
                    name = parts[0].trim()
                    address = if (parts.size > 1) parts[1].trim() else ""
                }
                val coordRegex = "\\[.*?\\]".toRegex()
                address = address.replace(coordRegex, "").trim()
                holder.tvName.text = if (address.isNotEmpty()) "$name - $address" else name
                holder.tvName.setTextColor(Color.WHITE)
            } else if (item.type == TreeType.MACROZONE) {
                val macroName = item.text
                var comuniStr = ""
                val macrozoneObj = fullData?.optJSONObject(item.parentRegion)?.optJSONObject("Macrozone")
                val arr = macrozoneObj?.optJSONArray(macroName)
                if (arr != null && arr.length() > 0) {
                    val firstEntry = arr.getString(0)
                    if (firstEntry.startsWith("<") && firstEntry.endsWith(">")) {
                        comuniStr = firstEntry.removeSurrounding("<", ">")
                    }
                }
                holder.tvName.text = macroName
                holder.tvName.setTextColor(Color.WHITE)
                if (comuniStr.isNotEmpty()) {
                    holder.tvNameMarquee.visibility = View.VISIBLE
                    holder.tvNameMarquee.text = "($comuniStr)"
                    holder.tvNameMarquee.isSelected = true
                } else {
                    holder.tvNameMarquee.visibility = View.GONE
                }
            } else {
                holder.tvName.text = cleanName
                holder.tvName.setTextColor(Color.WHITE)
            }

            // Preferiti (solo per Comuni)
            if (item.type == TreeType.COMUNE_DIRECT || item.type == TreeType.MACROZONE) {
                holder.ivTrailingStar.visibility = View.VISIBLE
                
                val isFav = sharedPrefs.getBoolean("fav_${cleanName}", false)
                holder.ivTrailingStar.setImageResource(if (isFav) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
                if (isFav) holder.ivTrailingStar.setColorFilter(Color.parseColor("#FFD700")) else holder.ivTrailingStar.clearColorFilter()

                holder.ivTrailingStar.setOnClickListener {
                    val currentPos = holder.bindingAdapterPosition
                    if (currentPos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val newFav = !sharedPrefs.getBoolean("fav_${cleanName}", false)
                    
                    FavoriteManager.setFavorite(this@GeoNavActivity, cleanName, newFav)
                    adapter.notifyItemChanged(currentPos)
                    setupDynamicDrawer()
                    FavoriteManager.persistFavoritesToFirebase(
                        this@GeoNavActivity,
                        settingsRepository,
                        lifecycleScope
                    )
                }
            } else {
                holder.ivTrailingStar.visibility = View.GONE
                holder.ivTrailingStar.setOnClickListener(null)
            }

            holder.itemView.setOnClickListener {
                val currentPos = holder.bindingAdapterPosition
                if (currentPos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (item.type == TreeType.PFS) openPfsMap(item.text)
                else if (item.type == TreeType.COMUNE_DIRECT || item.type == TreeType.MACROZONE) {
                    openComuneInPfsActivity(item, currentPos)
                } else if (item.isExpanded) collapseItem(item, currentPos)
                else expandItem(item, currentPos)
            }
        }

        override fun getItemCount() = displayList.size

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvGeoName)
            val tvNameMarquee: TextView = v.findViewById(R.id.tvGeoNameMarquee)
            val llContainer: View = v.findViewById(R.id.llContainer)
            val ivExpandIndicator: ImageView = v.findViewById(R.id.ivExpandIndicator)
            val ivTrailingStar: ImageView = v.findViewById(R.id.ivTrailingStar)
        }
    }
}
