package com.technicalwork.materiali

import android.content.Context
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class AppConfig(
    val companies: List<String> = emptyList(),
    val pfs_areas: List<String> = emptyList()
)

class ConfigManager(private val context: Context) {

    private val gson = Gson()
    private val client = OkHttpClient()
    private val configUrl = "https://raw.githubusercontent.com/gmaclol/Technicalwork-Materiali/master/lists/config.json"
    private val cacheFile = File(context.filesDir, "config_cache.json")
    private var cachedConfig: AppConfig? = null

    /**
     * Carica la configurazione prioritizzando:
     * 1) Cache locale (file scaricato l'ultima volta)
     * 2) Assets (default di fabbrica)
     */
    fun getConfig(): AppConfig {
        if (cachedConfig != null) return cachedConfig!!

        // 1. Tenta da Cache
        if (cacheFile.exists()) {
            try {
                cacheFile.inputStream().use { inputStream ->
                    val reader = InputStreamReader(inputStream)
                    cachedConfig = gson.fromJson(reader, AppConfig::class.java)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (cachedConfig != null) return cachedConfig!!

        // 2. Tenta da Assets (Fallback)
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

    /**
     * Scarica la nuova configurazione da GitHub in background.
     */
    suspend fun fetchRemoteConfig(): Boolean {
        return try {
            val request = Request.Builder().url(configUrl).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val content = response.body?.string()
                if (!content.isNullOrBlank()) {
                    cacheFile.writeText(content)
                    // Invalida cache in memoria per il prossimo utilizzo
                    cachedConfig = null 
                    return true
                }
            }
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getCompanies(): List<String> = getConfig().companies
    // Ignoriamo pfs_areas da config.json come richiesto, usiamo i preferiti salvati in GeoNavPrefs
    fun getPfsAreas(): List<String> = emptyList()

    val ITALIAN_REGIONS = listOf(
        "Abruzzo", "Basilicata", "Calabria", "Campania", "Emilia-Romagna", 
        "Friuli-Venezia Giulia", "Lazio", "Liguria", "Lombardia", "Marche", 
        "Molise", "Piemonte", "Puglia", "Sardegna", "Sicilia", "Toscana", 
        "Trentino-Alto Adige", "Umbria", "Valle d'Aosta", "Veneto"
    )

    suspend fun fetchRemoteRegionsJson() {
        coroutineScope {
            ITALIAN_REGIONS.map { region ->
                launch(Dispatchers.IO) {
                    val encodedRegion = region.replace(" ", "%20")
                    val url = "https://raw.githubusercontent.com/gmaclol/Technicalwork-Materiali/master/lists/Regioni/$encodedRegion.json"
                    val regionFile = File(context.filesDir, "$region.json")
                    try {
                        val request = Request.Builder().url(url).build()
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            val content = response.body?.string()
                            if (!content.isNullOrBlank()) {
                                regionFile.writeText(content)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.forEach { it.join() }
        }
    }
}
