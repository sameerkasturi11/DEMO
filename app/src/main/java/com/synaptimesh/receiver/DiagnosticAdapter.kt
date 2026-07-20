package com.synaptimesh.receiver

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.synaptimesh.receiver.R

class DiagnosticAdapter(private var items: List<DiagnosticItem>) : RecyclerView.Adapter<DiagnosticAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtStatusIcon: TextView = view.findViewById(R.id.txtStatusIcon)
        val txtTitle: TextView = view.findViewById(R.id.txtTitle)
        val txtMessage: TextView = view.findViewById(R.id.txtMessage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_diagnostic, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.txtTitle.text = item.title
        holder.txtMessage.text = item.message
        
        holder.txtStatusIcon.text = when (item.status) {
            DiagnosticStatus.PASS -> "🟢"
            DiagnosticStatus.WARNING -> "🟡"
            DiagnosticStatus.FAIL -> "🔴"
        }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<DiagnosticItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
