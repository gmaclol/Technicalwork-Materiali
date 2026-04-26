package com.technicalwork.materiali

import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await

/**
 * Dati di un singolo materiale scambiato.
 */
data class ExchangeItem(
    val label: String,
    val qtyFree: Int,
    val qtyUsed: Int
)

/**
 * Direzione dello scambio dal punto di vista di B (scanner).
 */
enum class ExchangeDirection {
    B_TAKES_FROM_A,
    B_GIVES_TO_A
}

/**
 * Log completo di uno scambio.
 */
data class ExchangeLog(
    val id: String = "",
    val fromDeviceId: String = "",
    val toDeviceId: String = "",
    val fromTechName: String = "",
    val toTechName: String = "",
    val company: String = "",
    val items: List<Map<String, Any>> = emptyList(),
    val direction: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val timestamp: Long = 0L,
    val processedByTarget: Boolean = false,
    val appVersion: String = ""
)

/**
 * Repository per tutte le operazioni Firestore legate allo scambio materiale.
 */
class ExchangeRepository {

    private val db = Firebase.firestore
    private val separatorRegex = Regex("^::.*::$|^;;.*;;$")

    /**
     * Legge l'inventario di un device remoto da Firestore.
     * Ritorna la lista materiali come List<ExcelRowData> oppure null se non trovato.
     */
    suspend fun fetchRemoteInventory(company: String, deviceId: String): List<ExcelRowData>? {
        return try {
            val doc = db.collection(company).document(deviceId).get().await()
            if (!doc.exists()) return null

            val materialiMap = doc.get("materiali") as? Map<*, *> ?: return null
            val ordine = doc.get("ordine") as? List<*>

            // Ricostruisce la lista nell'ordine originale
            if (ordine != null) {
                ordine.mapNotNull { label ->
                    val labelStr = label as? String ?: return@mapNotNull null
                    val value = materialiMap[labelStr]?.toString() ?: ""
                    ExcelRowData(labelStr, value)
                }
            } else {
                materialiMap.map { (key, value) ->
                    ExcelRowData(key.toString(), value.toString())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Legge il nome del tecnico dal documento Firestore di un device.
     */
    suspend fun fetchRemoteTechName(company: String, deviceId: String): String? {
        return try {
            val doc = db.collection(company).document(deviceId).get().await()
            doc.getString("tecnico")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Esegue lo scambio: aggiorna l'inventario remoto di A su Firestore e crea l'ExchangeLog.
     * L'inventario locale di B viene gestito dal chiamante (ExchangeActivity).
     */
    suspend fun executeExchange(
        fromDeviceId: String,
        toDeviceId: String,
        fromTechName: String,
        toTechName: String,
        company: String,
        items: List<ExchangeItem>,
        direction: ExchangeDirection,
        lat: Double?,
        lng: Double?
    ): Boolean {
        return try {
            // 1. Leggi l'inventario corrente di A da Firestore
            val currentInventory = fetchRemoteInventory(company, toDeviceId) ?: return false

            val parser = StockParser()

            // 2. Applica le modifiche all'inventario di A
            val updatedInventory = currentInventory.map { row ->
                val exchangeItem = items.find { it.label == row.label }
                if (exchangeItem != null) {
                    val stock = parser.parse(row.label, row.value)
                    val newStock = when (direction) {
                        ExchangeDirection.B_TAKES_FROM_A -> {
                            stock.copy(
                                free = (stock.free - exchangeItem.qtyFree).coerceAtLeast(0),
                                used = (stock.used - exchangeItem.qtyUsed).coerceAtLeast(0)
                            )
                        }
                        ExchangeDirection.B_GIVES_TO_A -> {
                            stock.copy(
                                free = stock.free + exchangeItem.qtyFree,
                                used = stock.used + exchangeItem.qtyUsed
                            )
                        }
                    }
                    ExcelRowData(row.label, parser.recompose(newStock))
                } else {
                    row
                }
            }

            // 3. Aggiorna Firestore di A con i nuovi valori
            val materialiMap = updatedInventory
                .filter { !it.label.trim().matches(separatorRegex) }
                .associate { it.label to it.value }

            val updateData = hashMapOf<String, Any>(
                "materiali" to materialiMap,
                "ordine" to updatedInventory
                    .filter { !it.label.trim().matches(separatorRegex) }
                    .map { it.label }
            )
            db.collection(company).document(toDeviceId)
                .set(updateData, SetOptions.merge())
                .await()

            // 4. Crea l'ExchangeLog
            val logData = hashMapOf<String, Any>(
                "fromDeviceId" to fromDeviceId,
                "toDeviceId" to toDeviceId,
                "fromTechName" to fromTechName,
                "toTechName" to toTechName,
                "company" to company,
                "items" to items.map { item ->
                    hashMapOf(
                        "label" to item.label,
                        "qtyFree" to item.qtyFree,
                        "qtyUsed" to item.qtyUsed
                    )
                },
                "direction" to direction.name,
                "timestamp" to System.currentTimeMillis(),
                "processedByTarget" to false,
                "appVersion" to "Ver ${BuildConfig.VERSION_NAME}"
            )
            lat?.let { logData["lat"] = it }
            lng?.let { logData["lng"] = it }

            db.collection("exchanges").add(logData).await()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Query: scambi pendenti per il mio device (dove sono il "target" = A).
     */
    suspend fun getPendingExchanges(myDeviceId: String): List<ExchangeLog> {
        return try {
            val snapshot = db.collection("exchanges")
                .whereEqualTo("toDeviceId", myDeviceId)
                .whereEqualTo("processedByTarget", false)
                .get()
                .await()

            snapshot.documents.map { doc ->
                ExchangeLog(
                    id = doc.id,
                    fromDeviceId = doc.getString("fromDeviceId") ?: "",
                    toDeviceId = doc.getString("toDeviceId") ?: "",
                    fromTechName = doc.getString("fromTechName") ?: "",
                    toTechName = doc.getString("toTechName") ?: "",
                    company = doc.getString("company") ?: "",
                    items = @Suppress("UNCHECKED_CAST") (doc.get("items") as? List<Map<String, Any>>) ?: emptyList(),
                    direction = doc.getString("direction") ?: "",
                    lat = doc.getDouble("lat"),
                    lng = doc.getDouble("lng"),
                    timestamp = doc.getLong("timestamp") ?: 0L,
                    processedByTarget = doc.getBoolean("processedByTarget") ?: false,
                    appVersion = doc.getString("appVersion") ?: ""
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Marca uno scambio come processato (A ha aggiornato il suo inventario locale).
     */
    suspend fun markAsProcessed(exchangeId: String): Boolean {
        return try {
            db.collection("exchanges").document(exchangeId)
                .update("processedByTarget", true)
                .await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * SnapshotListener per ricevere scambi in tempo reale quando l'app è aperta.
     * Restituisce la ListenerRegistration per poterla rimuovere in onDestroy.
     */
    fun listenForPendingExchanges(
        myDeviceId: String,
        onNew: (List<ExchangeLog>) -> Unit
    ): ListenerRegistration {
        return db.collection("exchanges")
            .whereEqualTo("toDeviceId", myDeviceId)
            .whereEqualTo("processedByTarget", false)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener

                val pending = snapshot.documents.map { doc ->
                    ExchangeLog(
                        id = doc.id,
                        fromDeviceId = doc.getString("fromDeviceId") ?: "",
                        toDeviceId = doc.getString("toDeviceId") ?: "",
                        fromTechName = doc.getString("fromTechName") ?: "",
                        toTechName = doc.getString("toTechName") ?: "",
                        company = doc.getString("company") ?: "",
                        items = @Suppress("UNCHECKED_CAST") (doc.get("items") as? List<Map<String, Any>>) ?: emptyList(),
                        direction = doc.getString("direction") ?: "",
                        lat = doc.getDouble("lat"),
                        lng = doc.getDouble("lng"),
                        timestamp = doc.getLong("timestamp") ?: 0L,
                        processedByTarget = doc.getBoolean("processedByTarget") ?: false,
                        appVersion = doc.getString("appVersion") ?: ""
                    )
                }
                if (pending.isNotEmpty()) {
                    onNew(pending)
                }
            }
    }
}
