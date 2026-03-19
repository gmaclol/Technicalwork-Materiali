package com.technicalwork.materiali

import android.content.Context
import android.content.SharedPreferences

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ExcelPrefs", Context.MODE_PRIVATE)

    var includeTechName: Boolean
        get() = prefs.getBoolean("include_tech_name", true)
        set(value) = prefs.edit().putBoolean("include_tech_name", value).apply()

    var includeDate: Boolean
        get() = prefs.getBoolean("include_date", true)
        set(value) = prefs.edit().putBoolean("include_date", value).apply()

    var technicianName: String?
        get() = prefs.getString("technician_name", null)
        set(value) = prefs.edit().putString("technician_name", value).apply()

    var lastSelectedCompany: String?
        get() = prefs.getString("last_selected_company", null)
        set(value) = prefs.edit().putString("last_selected_company", value).apply()

    var lastFileUri: String?
        get() = prefs.getString("last_file_uri", null)
        set(value) {
            val editor = prefs.edit()
            editor.putString("last_file_uri", value)
            if (lastSelectedCompany != null) {
                editor.putString("last_selected_company", lastSelectedCompany)
            }
            editor.apply()
        }

    var consumoFileUri: String?
        get() = prefs.getString("consumo_file_uri", null)
        set(value) = prefs.edit().putString("consumo_file_uri", value).apply()

    fun getCompanyFileUri(company: String): String? {
        return prefs.getString("uri_$company", null)
    }

    fun saveCompanyFileUri(company: String, uriString: String) {
        prefs.edit().putString("uri_$company", uriString).apply()
    }
    
    fun clearLastFileUri() {
        prefs.edit().remove("last_file_uri").apply()
    }
}
