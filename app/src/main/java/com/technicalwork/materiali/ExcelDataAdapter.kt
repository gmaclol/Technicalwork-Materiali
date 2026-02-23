package com.technicalwork.materiali

import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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

        holder.etLabel.removeTextChangedListener(holder.labelWatcher)
        holder.etValue.removeTextChangedListener(holder.valueWatcher)

        holder.etLabel.setText(item.label)
        holder.etValue.setText(item.value)

        updateStepButtonsUI(holder, item.value)

        holder.labelWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (item.label != s.toString()) {
                    item.label = s.toString()
                    if (!isUpdatingIndividually) onDataChanged()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        }

        holder.valueWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val newValue = s.toString()
                if (item.value != newValue) {
                    item.value = newValue
                    updateStepButtonsUI(holder, newValue)
                    if (!isUpdatingIndividually) onDataChanged()
                }
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
            isUpdatingIndividually = false
            onDataChanged()
        }

        holder.btnMinus.setOnClickListener {
            val currentVal = item.value.toIntOrNull() ?: 0
            val nextVal = if (currentVal > 0) currentVal - 1 else 0
            isUpdatingIndividually = true
            holder.etValue.setText(nextVal.toString())
            isUpdatingIndividually = false
            onDataChanged()
        }
    }

    private fun updateStepButtonsUI(holder: ViewHolder, value: String) {
        val context = holder.itemView.context
        val numericVal = value.toIntOrNull()
        val isNumeric = value.isEmpty() || numericVal != null
        
        // Visibilità pulsanti
        val visibility = if (isNumeric) View.VISIBLE else View.INVISIBLE
        holder.btnMinus.visibility = visibility
        holder.btnPlus.visibility = visibility

        // Stile del numero centrale
        if (numericVal != null && numericVal > 0) {
            // Numero > 0: Blu Gemini e Grassetto
            holder.etValue.setTextColor(ContextCompat.getColor(context, R.color.gemini_accent_blue))
            holder.etValue.setTypeface(null, Typeface.BOLD)
        } else {
            // Numero = 0 o Testo: Bianco/Grigio standard
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
        dataList.add(ExcelRowData("", ""))
        notifyItemInserted(dataList.size - 1)
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