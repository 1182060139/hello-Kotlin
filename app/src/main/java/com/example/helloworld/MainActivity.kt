package com.example.helloworld

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 示例 JSON（你导出的数据，我截取了一部分用于演示）
        val jsonString = """
        {
          "timestamp": 1780552261,
          "buildings": [
            { "data": 1000012, "lvl": 14, "timer": 288211 },
            { "data": 1000021, "lvl": 10, "timer": 17284 },
            { "data": 1000021, "lvl": 10, "timer": 17156 },
            { "data": 1000089, "lvl": 1, "timer": 417811 }
          ],
          "heroes": [
            { "data": 28000006, "lvl": 65, "timer": 245011 }
          ],
          "pets": [
            { "data": 73000003, "lvl": 12, "timer": 331411 }
          ],
          "buildings2": [
            { "data": 1000054, "lvl": 9, "timer": 54652 }
          ],
          "siege_machines": [
            { "data": 4000188, "lvl": 2, "timer": 569011 }
          ]
        }
        """.trimIndent()

        val upgrades = parseUpgrades(jsonString)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = UpgradeAdapter(upgrades)
    }

    private fun parseUpgrades(jsonString: String): List<UpgradeItem> {
        val json = JSONObject(jsonString)
        val timestampUtcSec = json.getLong("timestamp")
        val result = mutableListOf<UpgradeItem>()

        val keys = arrayOf(
            "buildings", "buildings2", "heroes", "heroes2",
            "pets", "siege_machines", "units", "units2", "spells"
        )
        for (key in keys) {
            val arr = json.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val timer = obj.optInt("timer", 0)
                if (timer > 0) {
                    val id = obj.getInt("data")
                    val endTimeMillis = (timestampUtcSec + timer) * 1000L
                    result.add(UpgradeItem(id, endTimeMillis))
                }
            }
        }
        return result.sortedBy { it.endTimeMillis }
    }
}
