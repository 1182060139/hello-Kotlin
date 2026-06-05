package com.example.helloworld

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class UpgradeAdapter(
    private val items: MutableList<UpgradeItem>,
    private val idToName: Map<Int, String>,
    private val onItemClick: (UpgradeItem, Int) -> Unit
) : RecyclerView.Adapter<UpgradeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvLevel: TextView = view.findViewById(R.id.tvLevel)
        val tvHelper: TextView = view.findViewById(R.id.tvHelper)
        val tvEndTime: TextView = view.findViewById(R.id.tvEndTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_upgrade, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val name = idToName[item.id] ?: "ID:${item.id}"
        holder.tvName.text = name
        holder.tvLevel.text = if (item.targetLevel > 0) " → ${item.targetLevel}级" else ""
        holder.tvHelper.text = if (item.helperRecurrent) "🛠️ 助手" else ""

        val displayTime = if (item.estimatedEndTimeMillis > 0) {
            item.estimatedEndTimeMillis
        } else {
            item.endTimeMillis
        }
        val shanghaiZone = ZoneId.of("Asia/Shanghai")
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val timeStr = Instant.ofEpochMilli(displayTime)
            .atZone(shanghaiZone)
            .toLocalDateTime()
            .format(formatter)

        holder.tvEndTime.text = if (item.estimatedEndTimeMillis > 0) {
            "预估: $timeStr"
        } else {
            "结束: $timeStr"
        }
        holder.tvEndTime.setTextColor(
            if (item.estimatedEndTimeMillis > 0) Color.parseColor("#FF5722") else Color.WHITE
        )

        holder.itemView.setOnClickListener {
            onItemClick(item, position)
        }
    }

    override fun getItemCount() = items.size
}