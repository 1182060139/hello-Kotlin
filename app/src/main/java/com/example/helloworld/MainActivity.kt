package com.example.helloworld

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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
    private val helperCooldowns = mutableMapOf<Int, Int>() // helperId -> cooldown秒

    private val PREFS_NAME = "clash_upgrade_prefs"
    private val KEY_JSON = "last_json"
    private val KEY_BUILDER_START = "builder_helper_start"
    private val KEY_LAB_START = "lab_helper_start"

    private var currentUpgrades = mutableListOf<UpgradeItem>()
    private lateinit var adapter: UpgradeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        tvError = findViewById(R.id.tvError)
        val btnPaste = findViewById<Button>(R.id.btnPaste)
        val btnTestReminder = findViewById<Button>(R.id.btnTestReminder)
        val btnSettings = findViewById<Button>(R.id.btnSettings) // 新增设置按钮

        recyclerView.layoutManager = LinearLayoutManager(this)

        createNotificationChannel()
        requestNotificationPermission()

        loadIdMapping()
        loadSavedData()

        btnPaste.setOnClickListener { loadFromClipboard() }
        btnTestReminder.setOnClickListener { scheduleTestReminder() }
        btnSettings.setOnClickListener { showSettingsDialog() } // 设置每日开始时间
    }

    // ---------- 通知渠道 ----------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "upgrade_reminder", "升级提醒", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "建筑或研究即将完成提醒" }
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    // ---------- ID 映射 ----------
    private fun loadIdMapping() { /* 保持原样，略 */ }

    // ---------- 持久化加载 ----------
    private fun loadSavedData() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedJson = prefs.getString(KEY_JSON, null)
        if (!savedJson.isNullOrBlank()) {
            try {
                val upgrades = parseUpgrades(savedJson)
                currentUpgrades.clear()
                currentUpgrades.addAll(upgrades)
                adapter = UpgradeAdapter(currentUpgrades, idToNameMap) { item, pos ->
                    handleHelperClick(item, pos)
                }
                recyclerView.adapter = adapter
                tvError.visibility = android.view.View.GONE
                scheduleAllReminders(currentUpgrades)
            } catch (e: Exception) { /* 错误处理 */ }
        } else {
            currentUpgrades.clear()
            adapter = UpgradeAdapter(currentUpgrades, idToNameMap) { item, pos -> }
            recyclerView.adapter = adapter
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

                    currentUpgrades.clear()
                    currentUpgrades.addAll(upgrades)
                    adapter = UpgradeAdapter(currentUpgrades, idToNameMap) { item, pos ->
                        handleHelperClick(item, pos)
                    }
                    recyclerView.adapter = adapter
                    tvError.visibility = android.view.View.GONE
                    Toast.makeText(this, "导入成功，${upgrades.size} 个升级项目", Toast.LENGTH_SHORT).show()
                    scheduleAllReminders(currentUpgrades)
                } catch (e: JSONException) { showError("剪贴板内容不是有效的 JSON 数据") }
                catch (e: Exception) { showError("解析出错: ${e.message}") }
            } else showError("剪贴板为空")
        } else showError("剪贴板无内容")
    }

    // ---------- 解析（含 helpers）----------
    private fun parseUpgrades(jsonString: String): List<UpgradeItem> {
        val json = JSONObject(jsonString)
        val timestampUtcSec = json.getLong("timestamp")

        // 解析 helpers 冷却时间
        helperCooldowns.clear()
        val helpersArr = json.optJSONArray("helpers")
        if (helpersArr != null) {
            for (i in 0 until helpersArr.length()) {
                val hObj = helpersArr.getJSONObject(i)
                val hId = hObj.getInt("data")
                val cd = hObj.optInt("helper_cooldown", 0)
                helperCooldowns[hId] = cd
            }
        }

        val result = mutableListOf<UpgradeItem>()
        val keys = arrayOf("buildings","buildings2","heroes","heroes2",
                          "pets","siege_machines","units","units2","spells")
        for (key in keys) {
            val arr = json.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val timer = obj.optInt("timer", 0)
                if (timer > 0) {
                    val id = obj.getInt("data")
                    val lvl = obj.optInt("lvl", 0)
                    val helper = obj.optBoolean("helper_recurrent", false)
                    val (cat, reduction) = getCategoryAndReduction(key)
                    val helperId = when (cat) {
                        "building", "hero" -> 93000000
                        "lab" -> 93000001
                        else -> 0
                    }
                    val endTimeMillis = (timestampUtcSec + timer) * 1000L
                    result.add(UpgradeItem(
                        id = id,
                        endTimeMillis = endTimeMillis,
                        targetLevel = lvl,
                        helperRecurrent = helper,
                        helperId = helperId,
                        dailyReductionSeconds = reduction
                    ))
                }
            }
        }
        return result.sortedBy { it.endTimeMillis }
    }

    private fun getCategoryAndReduction(key: String): Pair<String, Long> {
        return when (key) {
            "buildings", "buildings2", "traps" -> "building" to 28800L
            "heroes", "heroes2" -> "hero" to 28800L
            "units", "units2", "spells", "siege_machines" -> "lab" to 3600L
            else -> "other" to 0L
        }
    }

    // ---------- 助手预估时间交互 ----------
    private fun handleHelperClick(item: UpgradeItem, position: Int) {
        if (!item.helperRecurrent) return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val startKey = if (item.helperId == 93000001) KEY_LAB_START else KEY_BUILDER_START
        val currentStart = prefs.getString(startKey, "07:00") ?: "07:00"

        val builder = AlertDialog.Builder(this)
        builder.setTitle("输入助手每日可用时间 (HH:mm)")
        val input = EditText(this)
        input.setText(currentStart)
        input.hint = "例如 07:30"
        builder.setView(input)

        builder.setPositiveButton("计算") { dialog, _ ->
            val timeStr = input.text.toString().trim()
            if (timeStr.matches(Regex("\\d{1,2}:\\d{2}"))) {
                // 保存时间
                prefs.edit().putString(startKey, timeStr).apply()
                // 计算预估
                val estimated = calculateEstimatedEndTime(item, timeStr)
                if (estimated > 0) {
                    currentUpgrades[position] = item.copy(estimatedEndTimeMillis = estimated)
                    adapter.notifyItemChanged(position)
                    // 询问是否更新闹钟
                    AlertDialog.Builder(this)
                        .setTitle("是否按预估时间更新提醒？")
                        .setMessage("点击确定将重新设置闹钟")
                        .setPositiveButton("更新") { _, _ ->
                            // 应用预估时间到 endTimeMillis，并重新设置闹钟
                            currentUpgrades[position] = currentUpgrades[position].copy(
                                endTimeMillis = estimated,
                                estimatedEndTimeMillis = 0
                            )
                            adapter.notifyItemChanged(position)
                            scheduleAllReminders(currentUpgrades)
                        }
                        .setNegativeButton("仅查看", null)
                        .show()
                } else {
                    Toast.makeText(this, "计算失败，请检查输入", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "格式错误，请使用 HH:mm", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }

    private fun calculateEstimatedEndTime(item: UpgradeItem, dailyStartTimeStr: String): Long {
        val now = System.currentTimeMillis()
        val originalRemaining = item.endTimeMillis - now
        if (originalRemaining <= 0) return item.endTimeMillis

        val cooldownSeconds = helperCooldowns[item.helperId] ?: 0
        val reductionMillis = item.dailyReductionSeconds * 1000L

        // 解析每日开始时间
        val parts = dailyStartTimeStr.split(":")
        val hour = parts[0].toIntOrNull() ?: return item.endTimeMillis
        val minute = parts[1].toIntOrNull() ?: return item.endTimeMillis

        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        val nextDailyStart = if (todayStart > now) todayStart else todayStart + 86400000L

        // 冷却结束时间
        val cooldownEnd = now + cooldownSeconds * 1000L
        val firstAvailable = maxOf(nextDailyStart, cooldownEnd)

        if (firstAvailable >= item.endTimeMillis) return item.endTimeMillis

        val dayMillis = 86400000L
        val count = ((item.endTimeMillis - firstAvailable) / dayMillis) + 1
        val totalReduction = count * reductionMillis
        val newRemaining = maxOf(0L, originalRemaining - totalReduction)
        return now + newRemaining
    }

    // ---------- 设置每日开始时间对话框 ----------
    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val builderStart = prefs.getString(KEY_BUILDER_START, "07:00") ?: "07:00"
        val labStart = prefs.getString(KEY_LAB_START, "07:00") ?: "07:00"

        val inflater = layoutInflater
        val view = inflater.inflate(R.layout.dialog_settings, null) // 需自定义布局
        val etBuilder = view.findViewById<EditText>(R.id.etBuilderStart)
        val etLab = view.findViewById<EditText>(R.id.etLabStart)
        etBuilder.setText(builderStart)
        etLab.setText(labStart)

        AlertDialog.Builder(this)
            .setTitle("设置助手每日可用时间")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                prefs.edit().putString(KEY_BUILDER_START, etBuilder.text.toString().trim()).apply()
                prefs.edit().putString(KEY_LAB_START, etLab.text.toString().trim()).apply()
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 闹钟设置 ----------
    private fun scheduleAllReminders(upgrades: List<UpgradeItem>) { /* 保持之前的 setAlarmClock 逻辑 */ }

    private fun scheduleTestReminder() { /* 保持 */ }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = android.view.View.VISIBLE
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}