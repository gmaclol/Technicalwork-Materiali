package com.technicalwork.materiali

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView

class PfsActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout

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
    }
}
