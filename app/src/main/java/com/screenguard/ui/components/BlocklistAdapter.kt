package com.screenguard.ui.components

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.screenguard.R

class BlocklistAdapter(
    private val items: MutableList<String>,
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<BlocklistAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val domain: TextView = view.findViewById(R.id.domain_text)
        val removeBtn: ImageButton = view.findViewById(R.id.remove_btn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_blocked_domain, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val domain = items[position]
        holder.domain.text = domain
        holder.removeBtn.setOnClickListener { onRemove(domain) }
    }

    override fun getItemCount() = items.size
}
