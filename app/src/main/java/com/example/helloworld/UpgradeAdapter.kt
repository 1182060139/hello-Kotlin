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
    private val items: MutableList<UpgradeItem>,
    private val idToName: Map<Int, String>,
    private val onItemClick: (UpgradeItem, Int) -> Unit
) : RecyclerView.Adapter<UpgradeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvLevel: TextView = view.findViewById(R.id.tvLevel)
        val tvOriginalEndTime: TextView = view.findViewById(R.id.tvOriginalEndTime)
        val tvHelperStatus: TextView = view.findViewById(R.id.tvHelperStatus)
        val tvEstimatedEndTime: TextView = view.findViewById(R.id.tvEstimatedEndTime)
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

        val shanghaiZone = ZoneId.of("Asia/Shanghai")
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        // 原始结束时间
        val originalTimeStr = Instant.ofEpochMilli(item.endTimeMillis)
            .atZone(shanghaiZone)
            .toLocalDateTime()
            .format(formatter)
        holder.tvOriginalEndTime.text = "结束: $originalTimeStr"

        // 助手状态
        if (item.helperRecurrent) {
            holder.tvHelperStatus.visibility = View.VISIBLE
            holder.tvHelperStatus.text = "使用助手: 是"
        } else {
            holder.tvHelperStatus.visibility = View.GONE
        }

        // 预估时间
        if (item.helperRecurrent && item.estimatedEndTimeMillis > 0) {
            holder.tvEstimatedEndTime.visibility = View.VISIBLE
            val estimatedTimeStr = Instant.ofEpochMilli(item.estimatedEndTimeMillis)
                .atZone(shanghaiZone)
                .toLocalDateTime()
                .format(formatter)
            holder.tvEstimatedEndTime.text = "预估: $estimatedTimeStr"
        } else {
            holder.tvEstimatedEndTime.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            onItemClick(item, position)
        }
    }

    override fun getItemCount() = items.size
}