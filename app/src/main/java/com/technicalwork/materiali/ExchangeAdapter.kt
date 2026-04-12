package com.technicalwork.materiali

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

/**
 * Modello per ogni riga nel paniere di scambio.
 */
data class ExchangeRowData(
    val label: String,
    val availableFree: Int,    // quantità libera disponibile (di A)
    val availableUsed: Int,    // quantità sparata disponibile (di A)
    val hasUsedPart: Boolean,  // true se il valore originale conteneva "sparato"
    var selectedFree: Int = 0, // quantità libera selezionata per lo scambio
    var selectedUsed: Int = 0  // quantità sparata selezionata per lo scambio
)

class ExchangeAdapter(
    private val items: MutableList<ExchangeRowData>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<ExchangeAdapter.ExchangeViewHolder>() {

    // Limite massimo cambia in base alla direzione
    // Se B prende da A: max = disponibilità di A
    // Se B dà ad A: max = illimitato (B sa quanto ha)
    var isTakingFromA = true
        set(value) {
            field = value
            // Se cambiamo tab e torniamo in "Prendi", limitiamo i valori per non superare il tetto massimo del tecnico obbiettivo,
            // ma NON resettiamo a zero la lista corrente che distruggerebbe il lavoro dell'utente!
            if (value) {
                items.forEach { 
                    if (it.selectedFree > it.availableFree) it.selectedFree = it.availableFree
                    if (it.selectedUsed > it.availableUsed) it.selectedUsed = it.availableUsed
                }
            }
            notifyDataSetChanged()
            onSelectionChanged()
        }

    class ExchangeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMaterialLabel: TextView = view.findViewById(R.id.tvMaterialLabel)
        val tvAvailable: TextView = view.findViewById(R.id.tvAvailable)
        val layoutFreeQty: View = view.findViewById(R.id.layoutFreeQty)
        val tvFreeLabel: TextView = view.findViewById(R.id.tvFreeLabel)
        val btnFreeMinus: MaterialButton = view.findViewById(R.id.btnFreeMinus)
        val tvFreeQty: TextView = view.findViewById(R.id.tvFreeQty)
        val btnFreePlus: MaterialButton = view.findViewById(R.id.btnFreePlus)
        val layoutUsedQty: View = view.findViewById(R.id.layoutUsedQty)
        val tvUsedLabel: TextView = view.findViewById(R.id.tvUsedLabel)
        val btnUsedMinus: MaterialButton = view.findViewById(R.id.btnUsedMinus)
        val tvUsedQty: TextView = view.findViewById(R.id.tvUsedQty)
        val btnUsedPlus: MaterialButton = view.findViewById(R.id.btnUsedPlus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExchangeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_exchange_row, parent, false)
        return ExchangeViewHolder(view)
    }

    override fun onBindViewHolder(holder: ExchangeViewHolder, position: Int) {
        val item = items[position]

        holder.tvMaterialLabel.text = item.label

        // Info disponibilità
        val availText = if (item.hasUsedPart) {
            "Disp: ${item.availableFree} liberi, ${item.availableUsed} sparati"
        } else {
            "Disp: ${item.availableFree}"
        }
        holder.tvAvailable.text = availText

        // Riga Liberi
        holder.tvFreeLabel.text = if (item.hasUsedPart) "Liberi" else "Quantità"
        holder.tvFreeQty.text = item.selectedFree.toString()

        // Riga Sparati (visibile solo se c'è la parte "used")
        holder.layoutUsedQty.visibility = if (item.hasUsedPart) View.VISIBLE else View.GONE
        holder.tvUsedQty.text = item.selectedUsed.toString()

        // --- Pulsanti Free ---
        holder.btnFreePlus.setOnClickListener {
            val maxFree = item.availableFree
            if (item.selectedFree < maxFree) {
                item.selectedFree++
                holder.tvFreeQty.text = item.selectedFree.toString()
                onSelectionChanged()
            }
        }
        holder.btnFreeMinus.setOnClickListener {
            if (item.selectedFree > 0) {
                item.selectedFree--
                holder.tvFreeQty.text = item.selectedFree.toString()
                onSelectionChanged()
            }
        }

        // --- Pulsanti Used ---
        holder.btnUsedPlus.setOnClickListener {
            val maxUsed = item.availableUsed
            if (item.selectedUsed < maxUsed) {
                item.selectedUsed++
                holder.tvUsedQty.text = item.selectedUsed.toString()
                onSelectionChanged()
            }
        }
        holder.btnUsedMinus.setOnClickListener {
            if (item.selectedUsed > 0) {
                item.selectedUsed--
                holder.tvUsedQty.text = item.selectedUsed.toString()
                onSelectionChanged()
            }
        }
    }

    override fun getItemCount() = items.size

    /**
     * Restituisce solo gli item con almeno una quantità selezionata.
     */
    fun getSelectedItems(): List<ExchangeItem> {
        return items
            .filter { it.selectedFree > 0 || it.selectedUsed > 0 }
            .map { ExchangeItem(it.label, it.selectedFree, it.selectedUsed) }
    }

    /**
     * Verifica se c'è almeno una selezione.
     */
    fun hasSelection(): Boolean {
        return items.any { it.selectedFree > 0 || it.selectedUsed > 0 }
    }
}
