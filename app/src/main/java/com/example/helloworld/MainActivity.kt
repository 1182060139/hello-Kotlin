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
        val btnSettings = findViewById<Button>(R.id.btnSettings)

        recyclerView.layoutManager = LinearLayoutManager(this)

        createNotificationChannel()
        requestNotificationPermission()

        loadIdMapping()
        loadSavedData()

        btnPaste.setOnClickListener { loadFromClipboard() }
        btnTestReminder.setOnClickListener { scheduleTestReminder() }
        btnSettings.setOnClickListener { showSettingsDialog() }
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
                currentUpgrades.clear()
                currentUpgrades.addAll(upgrades)
                adapter = UpgradeAdapter(currentUpgrades, idToNameMap) { item, pos ->
                    handleHelperClick(item, pos)
                }
                recyclerView.adapter = adapter
                tvError.visibility = android.view.View.GONE
                scheduleAllReminders(currentUpgrades)
            } catch (e: Exception) {
                prefs.edit().remove(KEY_JSON).apply()
                currentUpgrades.clear()
                adapter = UpgradeAdapter(currentUpgrades, idToNameMap) { _, _ -> }
                recyclerView.adapter = adapter
                tvError.text = "保存的数据已过期，请重新导入"
                tvError.visibility = android.view.View.VISIBLE
            }
        } else {
            currentUpgrades.clear()
            adapter = UpgradeAdapter(currentUpgrades, idToNameMap) { _, _ -> }
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
                    val lvl = obj.optInt("lvl", 0)
                    val helper = obj.optBoolean("helper_recurrent", false)
                    val (cat, reduction) = getCategoryAndReduction(key)
                    val helperId = when (cat) {
                        "building", "hero" -> 93000000
                        "lab" -> 93000001
                        else -> 0
                    }
                    val endTimeMillis = (timestampUtcSec + timer) * 1000L
                    result.add(
                        UpgradeItem(
                            id = id,
                            endTimeMillis = endTimeMillis,
                            targetLevel = lvl,
                            helperRecurrent = helper,
                            helperId = helperId,
                            dailyReductionSeconds = reduction
                        )
                    )
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
                prefs.edit().putString(startKey, timeStr).apply()
                val estimated = calculateEstimatedEndTime(item, timeStr)
                if (estimated > 0) {
                    currentUpgrades[position] = item.copy(estimatedEndTimeMillis = estimated)
                    adapter.notifyItemChanged(position)
                    AlertDialog.Builder(this)
                        .setTitle("是否按预估时间更新提醒？")
                        .setMessage("点击确定将重新设置闹钟")
                        .setPositiveButton("更新") { _, _ ->
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

        val cooldownEnd = now + cooldownSeconds * 1000L
        val firstAvailable = maxOf(nextDailyStart, cooldownEnd)

        if (firstAvailable >= item.endTimeMillis) return item.endTimeMillis

        val dayMillis = 86400000L
        val count = ((item.endTimeMillis - firstAvailable) / dayMillis) + 1
        val totalReduction = count * reductionMillis
        val newRemaining = maxOf(0L, originalRemaining - totalReduction)
        return now + newRemaining
    }

    // ---------- 设置对话框 ----------
    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val builderStart = prefs.getString(KEY_BUILDER_START, "07:00") ?: "07:00"
        val labStart = prefs.getString(KEY_LAB_START, "07:00") ?: "07:00"

        val inflater = layoutInflater
        val view = inflater.inflate(R.layout.dialog_settings, null)
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

    // ---------- 闹钟设置（使用 setAlarmClock） ----------
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
        var nextReminderTime: Long = Long.MAX_VALUE
        var nextReminderName: String = ""

        for (item in upgrades) {
            val reminderTime = item.endTimeMillis - 30_000L
            if (reminderTime <= System.currentTimeMillis()) continue

            val intent = Intent(this, ReminderReceiver::class.java).apply {
                putExtra("id", item.id)
                putExtra("name", idToNameMap[item.id] ?: "ID:${item.id}")
                data = Uri.parse("custom://${item.uniqueKey}")
            }
            val requestCode = System.identityHashCode(item.uniqueKey)
            val pendingIntent = PendingIntent.getBroadcast(
                this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(reminderTime, pendingIntent),
                pendingIntent
            )

            newKeys.add(item.uniqueKey)

            if (reminderTime < nextReminderTime) {
                nextReminderTime = reminderTime
                nextReminderName = idToNameMap[item.id] ?: "ID:${item.id}"
            }
        }

        prefs.edit().putStringSet("reminder_keys", newKeys).apply()

        if (newKeys.isEmpty()) {
            Toast.makeText(this, "所有升级时间均已过去，未设置提醒", Toast.LENGTH_LONG).show()
        } else {
            val formatter = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
            val timeStr = java.time.Instant.ofEpochMilli(nextReminderTime)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .toLocalDateTime()
                .format(formatter)
            Toast.makeText(this, "已设置提醒，下次提醒：$timeStr ($nextReminderName)", Toast.LENGTH_LONG).show()
        }
    }

    private fun scheduleTestReminder() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("id", 9999)
            putExtra("name", "测试建筑")
            data = Uri.parse("custom://test_9999")
        }
        val requestCode = 9999
        val pendingIntent = PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerTime = System.currentTimeMillis() + 10_000L
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerTime, pendingIntent),
            pendingIntent
        )
        Toast.makeText(this, "测试通知将在10秒后弹出", Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = android.view.View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}