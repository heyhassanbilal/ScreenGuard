package com.screenguard.ui.components

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.screenguard.R
import com.screenguard.utils.AppUsageInfo
import java.util.concurrent.TimeUnit

class AppUsageAdapter(
    private val items: List<AppUsageInfo>,
    private val totalTime: Long,
    private val limitProvider: (String) -> Long,
    private val onSetLimit: (AppUsageInfo) -> Unit
) : RecyclerView.Adapter<AppUsageAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val name: TextView = view.findViewById(R.id.app_name)
        val limit: TextView = view.findViewById(R.id.app_limit_text)
        val time: TextView = view.findViewById(R.id.app_time)
        val progress: ProgressBar = view.findViewById(R.id.app_progress)
        val percent: TextView = view.findViewById(R.id.app_percent)
        val setLimitButton: Button = view.findViewById(R.id.set_limit_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_usage, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.icon.setImageDrawable(item.icon)
        holder.name.text = item.appName
        holder.time.text = item.totalTimeFormatted
        val limitMs = limitProvider(item.packageName)
        val limitMinutes = TimeUnit.MILLISECONDS.toMinutes(limitMs)
        holder.limit.text = if (limitMs > 0L) {
            "Daily limit: ${formatClockLimit(limitMinutes)}"
        } else {
            "No daily limit set"
        }
        holder.setLimitButton.text = if (limitMs > 0L) "CHANGE LIMIT" else "SET LIMIT"

        val pct = if (totalTime > 0) (item.totalTimeMs * 100 / totalTime).toInt() else 0
        holder.progress.progress = pct
        holder.percent.text = "$pct%"
        holder.setLimitButton.setOnClickListener { onSetLimit(item) }
    }

    override fun getItemCount() = items.size

    private fun formatLimit(minutes: Long): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return when {
            hours > 0 && mins > 0 -> "${hours}h ${mins}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    private fun formatClockLimit(minutes: Long): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return "%02d:%02d".format(hours, mins)
    }
}
