package com.technicalwork.materiali

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import androidx.recyclerview.widget.RecyclerView

data class ExcelRowData(var label: String, var value: String)

class ExcelDataAdapter(
    private var dataList: MutableList<ExcelRowData>,
    private val onDataChanged: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var isUpdatingIndividually = false
    private val separatorRegex = Regex("^::.*::$")

    companion object {
        private const val VIEW_TYPE_NORMAL = 0
        private const val VIEW_TYPE_SEPARATOR = 1
    }

    class NormalViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        val etLabel: EditText = view.findViewById(R.id.etLabel)
        val etValue: EditText = view.findViewById(R.id.etValue)
        val btnMinus: MaterialButton = view.findViewById(R.id.btnMinus)
        val btnPlus: MaterialButton = view.findViewById(R.id.btnPlus)
        var labelWatcher: TextWatcher? = null
        var valueWatcher: TextWatcher? = null
    }

    class SeparatorViewHolder(view: View, val tvSeparator: TextView) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int): Int {
        return if (dataList[position].label.trim().matches(separatorRegex)) {
            VIEW_TYPE_SEPARATOR
        } else {
            VIEW_TYPE_NORMAL
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == VIEW_TYPE_SEPARATOR) {
            val context = parent.context
            val density = context.resources.displayMetrics.density
            
            val frameLayout = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                val verticalPadding = (8 * density).toInt()
                setPadding(0, verticalPadding, 0, verticalPadding)
            }

            val textView = TextView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                val hPadding = (12 * density).toInt()
                val vPadding = (4 * density).toInt()
                setPadding(hPadding, vPadding, hPadding, vPadding)
                
                setTextColor(Color.parseColor("#CC0000"))
                setTypeface(null, Typeface.BOLD)
                
                val backgroundDrawable = GradientDrawable().apply {
                    setColor(Color.parseColor("#33CC0000"))
                    cornerRadius = 16 * density
                }
                background = backgroundDrawable
            }
            frameLayout.addView(textView)
            return SeparatorViewHolder(frameLayout, textView)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_data_row, parent, false)
            return NormalViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = dataList[position]
        
        if (holder is SeparatorViewHolder) {
            // Rimuove i :: iniziali e finali per la visualizzazione
            holder.tvSeparator.text = item.label.trim().removeSurrounding("::")
        } else if (holder is NormalViewHolder) {
            bindNormalRow(holder, item)
        }
    }

    private fun bindNormalRow(holder: NormalViewHolder, item: ExcelRowData) {
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

    private fun updateStepButtonsUI(holder: NormalViewHolder, value: String) {
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
