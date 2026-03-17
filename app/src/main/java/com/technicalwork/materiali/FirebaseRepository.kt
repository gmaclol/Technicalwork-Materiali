package com.technicalwork.materiali

import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FirebaseRepository {

    private val db = Firebase.firestore
    private val separatorRegex = Regex("^::.*::$")
    private val separatorExtraRegex = Regex("^;;.*;;$")

    /**
     * Sincronizza i dati dei materiali su Firestore.
     * Crea/Aggiorna un documento nella collection [company] con ID [technicianName].
     */
    suspend fun syncToFirestore(
        company: String,
        technicianName: String,
        materials: List<ExcelRowData>,
        lat: Double? = null,
        lng: Double? = null
    ) {
        if (technicianName.isBlank() || company.isBlank()) return

        try {
            val timestamp = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(Date())

            // Filtra i materiali escludendo i separatori e crea la mappa
            val materialiMap = materials
                .filter { item ->
                    val label = item.label.trim()
                    !label.matches(separatorRegex) && !label.matches(separatorExtraRegex)
                }
                .associate { it.label to it.value }

            val data = hashMapOf<String, Any>(
                "tecnico" to technicianName,
                "ultimo_aggiornamento" to timestamp,
                "appalto" to company,
                "materiali" to materialiMap,
                "ordine" to materials.filter { !it.label.trim().matches(separatorRegex) && !it.label.trim().matches(separatorExtraRegex) }.map { it.label }
            )

            // Aggiunta coordinate se presenti
            lat?.let { data["lat"] = it }
            lng?.let { data["lng"] = it }

            // Salva su Firestore: collection = company, document ID = technicianName
            db.collection(company)
                .document(technicianName)
                .set(data, SetOptions.merge())
                .await()

        } catch (e: Exception) {
            // Fallisce silenziosamente come richiesto
            e.printStackTrace()
        }
    }
}
