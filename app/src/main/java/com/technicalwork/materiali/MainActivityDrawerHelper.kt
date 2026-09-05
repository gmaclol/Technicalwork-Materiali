package com.technicalwork.materiali

import android.app.Activity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationView

object MainActivityDrawerHelper {

    /**
     * Associa i listener dell'header e costruisce i bottoni aziendali dinamici nel Drawer.
     */
    fun bindDrawer(
        activity: Activity,
        navigationView: NavigationView,
        companies: List<String>,
        onLogoClick: () -> Unit,
        onPfsLogoClick: () -> Unit,
        onCompanyClick: (String) -> Unit,
        onCompanyLongClick: (String) -> Unit
    ) {
        val headerView = navigationView.getHeaderView(0)
        
        // 1. Setup Click Loghi dell'Header
        val appLogo = headerView.findViewById<ImageView>(R.id.app_logo)
        val pfsLogo = headerView.findViewById<ImageView>(R.id.pfs_logo)
        
        val isPfsEnabled = FavoriteManager.isPfsEnabled(activity)
        pfsLogo?.visibility = if (isPfsEnabled) View.VISIBLE else View.GONE

        appLogo?.setOnClickListener {
            onLogoClick()
        }
        
        pfsLogo?.setOnClickListener {
            if (FavoriteManager.isPfsEnabled(activity)) {
                onPfsLogoClick()
            }
        }

        // 2. Setup Bottoni Aziendali Dinamici
        val container = navigationView.findViewById<LinearLayout>(R.id.llCompaniesContainer) ?: return
        container.removeAllViews()

        companies.forEachIndexed { index, company ->
            val button = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonTonalStyle)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * activity.resources.displayMetrics.density).toInt()
            )
            if (index == 0) {
                params.topMargin = (8 * activity.resources.displayMetrics.density).toInt()
            }
            button.layoutParams = params
            button.text = company
            button.icon = ContextCompat.getDrawable(activity, android.R.drawable.ic_menu_add)
            button.cornerRadius = (28 * activity.resources.displayMetrics.density).toInt()
            button.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            
            button.setOnClickListener {
                onCompanyClick(company)
            }
            
            button.setOnLongClickListener {
                onCompanyLongClick(company)
                true
            }
            
            container.addView(button)
        }
    }
}
