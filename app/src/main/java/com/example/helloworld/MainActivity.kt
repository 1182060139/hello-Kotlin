package com.example.helloworld

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

        // *** 关键：先创建通知渠道，让系统知道这个应用会发通知 ***
        createNotificationChannel()

        // 然后请求通知权限（Android 13+）
        requestNotificationPermission()

        loadIdMapping()
        loadSavedData()

        btnPaste.setOnClickListener {
            loadFromClipboard()
        }
    }

    /** 创建通知渠道，必须在发通知和申请权限之前调用 */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "upgrade_reminder",
                "升级提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "建筑或研究即将完成提醒"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /** 请求通知权限，并处理“不再提示”的情况 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }

    // ---------- ID 映射 ----------
    private fun loadIdMapping() {
        try {
            val files = assets.list("") ?: emptyArray()
            if (!files.contains("id_mapping.json")) {
                Toast.makeText(this, "未找到 id_mapping.json 文件", Toast.LENGTH_LONG).show()
                return
            }
            val jsonString = assets.open("id_mapping.json").bufferedReader().use { it.readText() }
            val root = JSONObject(jsonString)
            val map = mutableMapOf<Int, String>()
            val categories = root.keys()
            while (categories.hasNext()) {
                val category = categories.next()
                val arr = root.optJSONArray(category) ?: continue
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optInt("_id", -1)
                    val name = obj.optString("name", "")
                    if (id != -1 && name.isNotEmpty()) {
                        map[id] = name
                    }
                }
            }
            idToNameMap = map
            Toast.makeText(this, "映射加载成功，共 ${map.size} 项", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            idToNameMap = emptyMap()
            Toast.makeText(this, "映射文件加载失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- 持久化加载 ----------
    private fun loadSavedData() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedJson = prefs.getString(KEY_JSON, null)
        if (!savedJson.isNullOrBlank()) {
            try {
                val upgrades = parseUpgrades(savedJson)
                recyclerView.adapter = UpgradeAdapter(upgrades, idToNameMap)
                tvError.visibility = android.view.View.GONE
                scheduleAllReminders(upgrades)
            } catch (e: Exception) {
                prefs.edit().remove(KEY_JSON).apply()
                recyclerView.adapter = UpgradeAdapter(emptyList(), idToNameMap)
                tvError.text = "保存的数据已过期，请重新导入"
                tvError.visibility = android.view.View.VISIBLE
            }
        } else {
            recyclerView.adapter = UpgradeAdapter(emptyList(), idToNameMap)
        }
    }

    // ---------- 剪贴板导入 ----------
    private fun loadFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text.toString()
            if (text.isNotBlank()) {
                try {
                    val upgrades = parseUpgrades(text)
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putString(KEY_JSON, text).apply()

                    recyclerView.adapter = UpgradeAdapter(upgrades, idToNameMap)
                    tvError.visibility = android.view.View.GONE
                    Toast.makeText(this, "导入成功，${upgrades.size} 个升级项目", Toast.LENGTH_SHORT).show()

                    scheduleAllReminders(upgrades)
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

    // ---------- 闹钟设置 ----------
    private fun scheduleAllReminders(upgrades: List<UpgradeItem>) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val oldKeys = prefs.getStringSet("reminder_keys", emptySet()) ?: emptySet()
        for (key in oldKeys) {
            val intent = Intent(this, ReminderReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this, key.hashCode(), intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let { alarmManager.cancel(it) }
        }

        val newKeys = mutableSetOf<String>()
        for (item in upgrades) {
            val reminderTime = item.endTimeMillis - 30_000L
            if (reminderTime <= System.currentTimeMillis()) continue

            val intent = Intent(this, ReminderReceiver::class.java).apply {
                putExtra("id", item.id)
                putExtra("name", idToNameMap[item.id] ?: "ID:${item.id}")
            }
            val requestCode = item.uniqueKey.hashCode()
            val pendingIntent = PendingIntent.getBroadcast(
                this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminderTime,
                pendingIntent
            )
            newKeys.add(item.uniqueKey)
        }
        prefs.edit().putStringSet("reminder_keys", newKeys).apply()
    }

    // ---------- JSON 解析 ----------
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
                val obj = arr.optJSONObject(i) ?: continue
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

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = android.view.View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}