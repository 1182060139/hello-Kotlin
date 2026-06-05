package com.example.helloworld

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONException
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvError: TextView
    private var idToNameMap: Map<Int, String> = emptyMap()

    private val PREFS_NAME = "clash_upgrade_prefs"
    private val KEY_JSON = "last_json"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        tvError = findViewById(R.id.tvError)
        val btnPaste = findViewById<Button>(R.id.btnPaste)

        recyclerView.layoutManager = LinearLayoutManager(this)

        // 1. 加载 ID → 名称映射
        loadIdMapping()

        // 2. 尝试加载之前保存的数据
        loadSavedData()

        btnPaste.setOnClickListener {
            loadFromClipboard()
        }
    }

    /** 从 assets/id_mapping.json 读取所有 _id 和 name，构建映射表 */
    private fun loadIdMapping() {
        try {
            val jsonString = assets.open("id_mapping.json").bufferedReader().use { it.readText() }
            val root = JSONObject(jsonString)
            val map = mutableMapOf<Int, String>()

            // 遍历 JSON 对象的所有键（类别），每个键对应一个数组
            val categories = root.keys()
            while (categories.hasNext()) {
                val category = categories.next()
                val arr = root.optJSONArray(category) ?: continue
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.getInt("_id")
                    val name = obj.getString("name")
                    map[id] = name
                }
            }
            idToNameMap = map
        } catch (e: Exception) {
            idToNameMap = emptyMap()
            Toast.makeText(this, "映射文件加载失败，将显示 ID", Toast.LENGTH_SHORT).show()
        }
    }

    /** 从 SharedPreferences 加载上次保存的 JSON 并展示 */
    private fun loadSavedData() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedJson = prefs.getString(KEY_JSON, null)
        if (!savedJson.isNullOrBlank()) {
            try {
                val upgrades = parseUpgrades(savedJson)
                recyclerView.adapter = UpgradeAdapter(upgrades, idToNameMap)
                tvError.visibility = android.view.View.GONE
            } catch (e: Exception) {
                // 保存的数据损坏了，清空并显示空列表
                prefs.edit().remove(KEY_JSON).apply()
                recyclerView.adapter = UpgradeAdapter(emptyList(), idToNameMap)
                tvError.text = "保存的数据已过期，请重新导入"
                tvError.visibility = android.view.View.VISIBLE
            }
        } else {
            recyclerView.adapter = UpgradeAdapter(emptyList(), idToNameMap)
        }
    }

    /** 从剪贴板读取 JSON，解析并保存 */
    private fun loadFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text.toString()
            if (text.isNotBlank()) {
                try {
                    val upgrades = parseUpgrades(text)
                    // 保存原始 JSON 到 SharedPreferences
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putString(KEY_JSON, text).apply()

                    recyclerView.adapter = UpgradeAdapter(upgrades, idToNameMap)
                    tvError.visibility = android.view.View.GONE
                    Toast.makeText(this, "导入成功，找到 ${upgrades.size} 个升级项目", Toast.LENGTH_SHORT).show()
                } catch (e: JSONException) {
                    showError("剪贴板内容不是有效的 JSON 数据")
                } catch (e: Exception) {
                    showError("解析出错: ${e.message}")
                }
            } else {
                showError("剪贴板为空")
            }
        } else {
            showError("剪贴板无内容")
        }
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = android.view.View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 解析游戏导出的 JSON，提取所有 timer > 0 的项目 */
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