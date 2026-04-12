package com.technicalwork.materiali

import android.content.Context
import com.google.gson.Gson
import java.io.InputStreamReader

data class AppConfig(
    val companies: List<String> = emptyList(),
    val pfs_areas: List<String> = emptyList()
)

class ConfigManager(private val context: Context) {

    private val gson = Gson()
    private var cachedConfig: AppConfig? = null

    /**
     * Carica la configurazione dal file JSON negli assets.
     */
    fun getConfig(): AppConfig {
        if (cachedConfig != null) return cachedConfig!!

        return try {
            context.assets.open("config.json").use { inputStream ->
                val reader = InputStreamReader(inputStream)
                val config = gson.fromJson(reader, AppConfig::class.java)
                cachedConfig = config
                config ?: AppConfig()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            AppConfig()
        }
    }

    fun getCompanies(): List<String> = getConfig().companies
    fun getPfsAreas(): List<String> = getConfig().pfs_areas
}
