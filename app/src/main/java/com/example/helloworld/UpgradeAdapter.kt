package com.example.helloworld

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class UpgradeAdapter(
    private val items: List<UpgradeItem>,
    private val idToName: Map<Int, String>
) : RecyclerView.Adapter<UpgradeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvBuildingId: TextView = view.findViewById(R.id.tvBuildingId)
        val tvEndTime: TextView = view.findViewById(R.id.tvEndTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_upgrade, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val name = idToName[item.id] ?: "ID: ${item.id}"
        holder.tvBuildingId.text = name

        val shanghaiZone = ZoneId.of("Asia/Shanghai")
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val endTimeStr = Instant.ofEpochMilli(item.endTimeMillis)
            .atZone(shanghaiZone)
            .toLocalDateTime()
            .format(formatter)
        holder.tvEndTime.text = "结束时间: $endTimeStr"
    }

    override fun getItemCount() = items.size
}