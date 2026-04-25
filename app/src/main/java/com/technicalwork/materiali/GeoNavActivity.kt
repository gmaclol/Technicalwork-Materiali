package com.technicalwork.materiali

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class GeoNavActivity : AppCompatActivity() {

    private lateinit var rvGeoNav: RecyclerView
    private lateinit var pbGeoNav: ProgressBar
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvCustomTitle: TextView
    
    private var fullData: JSONObject? = null
    private val displayList = mutableListOf<TreeItem>()
    private val adapter = GeoNavAdapter()

    enum class TreeType { REGION, CAT_MACRO, CAT_COMUNI, MACROZONE, COMUNE_IN_MACRO, COMUNE_DIRECT, PFS }

    class TreeItem(
        val text: String,
        val level: Int,
        val type: TreeType,
        var isExpanded: Boolean = false,
        var hasChildren: Boolean = true
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_geo_nav)

        toolbar = findViewById(R.id.toolbar)
        rvGeoNav = findViewById(R.id.rvGeoNav)
        pbGeoNav = findViewById(R.id.pbGeoNav)
        tvCustomTitle = findViewById(R.id.customToolbarTitle)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        tvCustomTitle.text = "GeoNav"
        
        toolbar.setNavigationOnClickListener {
            if (!handleBack()) finish()
        }

        rvGeoNav.layoutManager = LinearLayoutManager(this)
        rvGeoNav.adapter = adapter

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!handleBack()) finish()
            }
        })

        loadJsonData()
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

    private fun loadJsonData() {
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { pbGeoNav.visibility = View.VISIBLE }
            try {
                val jsonString = assets.open("Piemonte.json").bufferedReader().use { it.readText() }
                fullData = JSONObject(jsonString)
                withContext(Dispatchers.Main) {
                    displayList.add(TreeItem("Piemonte", 0, TreeType.REGION))
                    adapter.notifyItemInserted(0)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GeoNavActivity, "Errore: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) { pbGeoNav.visibility = View.GONE }
            }
        }
    }

    private fun expandItem(item: TreeItem, position: Int) {
        item.isExpanded = true
        val children = mutableListOf<TreeItem>()
        val nextLevel = item.level + 1
        
        val piemonte = fullData?.optJSONObject("Piemonte")
        
        when (item.type) {
            TreeType.REGION -> {
                children.add(TreeItem("Macrozone", nextLevel, TreeType.CAT_MACRO))
                children.add(TreeItem("Comuni", nextLevel, TreeType.CAT_COMUNI))
            }
            TreeType.CAT_MACRO -> {
                val obj = piemonte?.optJSONObject("Macrozone")
                obj?.keys()?.let { keys ->
                    while (keys.hasNext()) {
                        children.add(TreeItem(keys.next(), nextLevel, TreeType.MACROZONE))
                    }
                }
                children.sortBy { it.text }
            }
            TreeType.CAT_COMUNI -> {
                val obj = piemonte?.optJSONObject("Comuni")
                obj?.keys()?.let { keys ->
                    while (keys.hasNext()) {
                        children.add(TreeItem(keys.next(), nextLevel, TreeType.COMUNE_DIRECT))
                    }
                }
                children.sortBy { it.text }
            }
            TreeType.MACROZONE -> {
                val array = piemonte?.optJSONObject("Macrozone")?.optJSONArray(item.text)
                array?.let {
                    for (i in 0 until it.length()) {
                        val entry = it.getString(i)
                        if (entry.startsWith("<") && entry.endsWith(">")) {
                            children.add(TreeItem(entry, nextLevel, TreeType.COMUNE_IN_MACRO))
                        }
                    }
                }
            }
            TreeType.COMUNE_IN_MACRO -> {
                var macroName = ""
                for (i in position - 1 downTo 0) {
                    if (displayList[i].type == TreeType.MACROZONE) {
                        macroName = displayList[i].text
                        break
                    }
                }
                val array = piemonte?.optJSONObject("Macrozone")?.optJSONArray(macroName)
                array?.let {
                    var found = false
                    for (i in 0 until it.length()) {
                        val entry = it.getString(i)
                        if (entry == item.text) { found = true; continue }
                        if (found) {
                            if (entry.startsWith("<") && entry.endsWith(">")) break
                            children.add(TreeItem(entry, nextLevel, TreeType.PFS, hasChildren = false))
                        }
                    }
                }
            }
            TreeType.COMUNE_DIRECT -> {
                val array = piemonte?.optJSONObject("Comuni")?.optJSONArray(item.text)
                array?.let {
                    for (i in 0 until it.length()) {
                        children.add(TreeItem(it.getString(i), nextLevel, TreeType.PFS, hasChildren = false))
                    }
                }
            }
            TreeType.PFS -> {}
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

    private fun sendResult(pfs: String) {
        val intent = Intent(this, PfsActivity::class.java).apply {
            putExtra("SELECTED_PFS_CONTENT", pfs)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
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

            // Gestione indicatori
            if (item.hasChildren && item.type != TreeType.PFS) {
                holder.ivExpandIndicator.visibility = View.VISIBLE
                holder.ivExpandIndicator.rotation = if (item.isExpanded) 90f else 0f
                holder.ivActionIndicator.visibility = View.VISIBLE
            } else {
                holder.ivExpandIndicator.visibility = View.INVISIBLE
                holder.ivActionIndicator.visibility = View.GONE
            }

            // Testo e Colori
            if (item.type == TreeType.COMUNE_IN_MACRO) {
                val clean = item.text.removeSurrounding("<", ">")
                holder.tvName.text = clean
                holder.tvName.setTextColor(Color.RED)
            } else {
                holder.tvName.text = item.text
                holder.tvName.setTextColor(Color.WHITE)
            }

            holder.itemView.setOnClickListener {
                if (item.type == TreeType.PFS) sendResult(item.text)
                else if (item.isExpanded) collapseItem(item, position)
                else expandItem(item, position)
            }
        }

        override fun getItemCount() = displayList.size

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvGeoName)
            val llContainer: View = v.findViewById(R.id.llContainer)
            val ivExpandIndicator: android.widget.ImageView = v.findViewById(R.id.ivExpandIndicator)
            val ivActionIndicator: android.widget.ImageView = v.findViewById(R.id.ivActionIndicator)
        }
    }
}
