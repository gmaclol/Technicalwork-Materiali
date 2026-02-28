package com.technicalwork.materiali

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import androidx.recyclerview.widget.RecyclerView

data class ExcelRowData(var label: String, var value: String)

class ExcelDataAdapter(
    private var dataList: MutableList<ExcelRowData>,
    private val onDataChanged: () -> Unit
) : RecyclerView.Adapter<ExcelDataAdapter.ViewHolder>() {

    private var isUpdatingIndividually = false

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        val etLabel: EditText = view.findViewById(R.id.etLabel)
        val etValue: EditText = view.findViewById(R.id.etValue)
        val btnMinus: MaterialButton = view.findViewById(R.id.btnMinus)
        val btnPlus: MaterialButton = view.findViewById(R.id.btnPlus)
        var labelWatcher: TextWatcher? = null
        var valueWatcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_data_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = dataList[position]
        val context = holder.itemView.context

        holder.etLabel.removeTextChangedListener(holder.labelWatcher)
        holder.etValue.removeTextChangedListener(holder.valueWatcher)
        holder.etLabel.onFocusChangeListener = null
        holder.etValue.onFocusChangeListener = null

        // Stato iniziale: Visualizzazione (TextView)
        holder.tvLabel.visibility = View.VISIBLE
        holder.etLabel.visibility = View.GONE
        holder.tvLabel.text = item.label
        holder.etLabel.setText(item.label)
        holder.etValue.setText(item.value)
        holder.tvLabel.isSelected = true // Attiva Marquee

        updateStepButtonsUI(holder, item.value)

        // Click sulla TextView -> Passa a modalità editing (EditText)
        holder.tvLabel.setOnClickListener {
            holder.tvLabel.visibility = View.GONE
            holder.etLabel.visibility = View.VISIBLE
            holder.etLabel.requestFocus()
            
            // Apre la tastiera automaticamente
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(holder.etLabel, InputMethodManager.SHOW_IMPLICIT)
        }

        // Listener per etLabel: torna a TextView alla perdita del focus
        holder.etLabel.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !isUpdatingIndividually) {
                val nuovoTesto = holder.etLabel.text.toString()
                item.label = nuovoTesto
                holder.tvLabel.text = nuovoTesto
                
                holder.etLabel.visibility = View.GONE
                holder.tvLabel.visibility = View.VISIBLE
                holder.tvLabel.isSelected = true
                
                onDataChanged() // Scatena il merge/undo in MainActivity
            }
        }

        // Listener per etValue: mantiene il comportamento standard (trigger onDataChanged al focus loss)
        holder.etValue.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !isUpdatingIndividually) {
                onDataChanged()
            }
        }

        // Aggiorna il modello mentre l'utente scrive (senza scatenare merge)
        holder.labelWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                item.label = s.toString()
            }
            override fun afterTextChanged(s: Editable?) {}
        }

        holder.valueWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                item.value = s.toString()
                updateStepButtonsUI(holder, item.value)
            }
            override fun afterTextChanged(s: Editable?) {}
        }

        holder.etLabel.addTextChangedListener(holder.labelWatcher)
        holder.etValue.addTextChangedListener(holder.valueWatcher)

        holder.btnPlus.setOnClickListener {
            val currentVal = item.value.toIntOrNull() ?: 0
            val nextVal = currentVal + 1
            isUpdatingIndividually = true
            holder.etValue.setText(nextVal.toString())
            item.value = nextVal.toString()
            isUpdatingIndividually = false
            onDataChanged()
        }

        holder.btnMinus.setOnClickListener {
            val currentVal = item.value.toIntOrNull() ?: 0
            val nextVal = if (currentVal > 0) currentVal - 1 else 0
            isUpdatingIndividually = true
            holder.etValue.setText(nextVal.toString())
            item.value = nextVal.toString()
            isUpdatingIndividually = false
            onDataChanged()
        }
    }

    private fun updateStepButtonsUI(holder: ViewHolder, value: String) {
        val context = holder.itemView.context
        val numericVal = value.toIntOrNull()
        val isNumeric = value.isEmpty() || numericVal != null
        
        val visibility = if (isNumeric) View.VISIBLE else View.INVISIBLE
        holder.btnMinus.visibility = visibility
        holder.btnPlus.visibility = visibility

        if (numericVal != null && numericVal > 0) {
            holder.etValue.setTextColor(ContextCompat.getColor(context, R.color.gemini_accent_blue))
            holder.etValue.setTypeface(null, Typeface.BOLD)
        } else {
            holder.etValue.setTextColor(ContextCompat.getColor(context, R.color.gemini_text_main))
            holder.etValue.setTypeface(null, Typeface.NORMAL)
        }
    }

    override fun getItemCount() = dataList.size

    fun getData(): List<ExcelRowData> = dataList.toList()

    fun updateData(newData: List<ExcelRowData>) {
        isUpdatingIndividually = true
        dataList.clear()
        dataList.addAll(newData)
        notifyDataSetChanged()
        isUpdatingIndividually = false
    }

    fun addRow() {
        dataList.add(0, ExcelRowData("", "")) // Aggiunge in cima per visibilità
        notifyItemInserted(0)
        onDataChanged()
    }

    fun removeRow(position: Int) {
        if (position in dataList.indices) {
            dataList.removeAt(position)
            notifyItemRemoved(position)
            onDataChanged()
        }
    }
}
