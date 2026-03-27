package com.technicalwork.materiali

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText

data class PfsItem(
    val name: String,
    val address: String,
    val isMissing: Boolean,
    var isExpanded: Boolean = false
)

class PfsAdapter(
    private var items: List<PfsItem>,
    private val onSubmitAddress: (PfsItem, String) -> Unit
) : RecyclerView.Adapter<PfsAdapter.PfsViewHolder>() {

    fun updateData(newItems: List<PfsItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PfsViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pfs, parent, false)
        return PfsViewHolder(view)
    }

    override fun onBindViewHolder(holder: PfsViewHolder, position: Int) {
        val item = items[position]
        holder.tvPfsName.text = item.name

        if (item.isMissing) {
            holder.tvPfsName.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.gemini_destructive))
        } else {
            holder.tvPfsName.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.gemini_text_main))
        }

        holder.llExpanded.visibility = if (item.isExpanded) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener {
            item.isExpanded = !item.isExpanded
            notifyItemChanged(position)
        }

        if (item.isMissing) {
            holder.btnMap.visibility = View.GONE
            holder.llMissingAddress.visibility = View.VISIBLE
            
            holder.btnSubmitAddress.setOnClickListener {
                val newAddr = holder.etNewAddress.text.toString()
                if (newAddr.isNotBlank()) {
                    onSubmitAddress(item, newAddr)
                    holder.etNewAddress.text?.clear()
                    item.isExpanded = false
                    notifyItemChanged(position)
                }
            }
        } else {
            holder.llMissingAddress.visibility = View.GONE
            holder.btnMap.visibility = View.VISIBLE
            holder.btnMap.text = "Mappa: ${item.address}"
            
            holder.btnMap.setOnClickListener {
                val uri = Uri.parse("geo:0,0?q=${Uri.encode(item.address)}")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.setPackage("com.google.android.apps.maps")
                if (intent.resolveActivity(holder.itemView.context.packageManager) != null) {
                    holder.itemView.context.startActivity(intent)
                } else {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
                    holder.itemView.context.startActivity(fallbackIntent)
                }
            }
        }
    }

    override fun getItemCount() = items.size

    class PfsViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPfsName: TextView = view.findViewById(R.id.tvPfsName)
        val llExpanded: LinearLayout = view.findViewById(R.id.llExpanded)
        val btnMap: Button = view.findViewById(R.id.btnMap)
        val llMissingAddress: LinearLayout = view.findViewById(R.id.llMissingAddress)
        val etNewAddress: TextInputEditText = view.findViewById(R.id.etNewAddress)
        val btnSubmitAddress: Button = view.findViewById(R.id.btnSubmitAddress)
    }
}
