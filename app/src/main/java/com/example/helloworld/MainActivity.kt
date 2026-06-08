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
import android.net.Uri
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
import java.util.Calendar

class MainActivity : Activity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvError: TextView
    private var idToNameMap: Map<Int, String> = emptyMap()

    private val PREFS_NAME = "clash_upgrade_prefs"
    private val KEY_JSON = "last_json"

    private var currentUpgrades = mutableListOf<UpgradeItem>()
    private lateinit var adapter: UpgradeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        tvError = findViewById(R.id.tvError)
        val btnPaste = findViewById<Button>(R.id.btnPaste)
        val btnTestReminder = findViewById<Button>(R.id.btnTestReminder)

        recyclerView.layoutManager = LinearLayoutManager(this)

        createNotificationChannel()
        requestNotificationPermission()

        loadIdMapping()
        loadSavedData()

        btnPaste.setOnClickListener { loadFromClipboard() }
        btnTestReminder.setOnClickListener { scheduleTestReminder() }
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
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
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
                    if (id != -1 && name.isNotEmpty()) map[id] = name
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
                    onItemClick(item, pos)
                }
                recyclerView.adapter = adapter
                tvError.visibility = android.view.View.GONE
                scheduleAllReminders()
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
                        onItemClick(item, pos)
                    }
                    recyclerView.adapter = adapter
                    tvError.visibility = android.view.View.GONE
                    Toast.makeText(this, "导入成功，${upgrades.size} 个升级项目", Toast.LENGTH_SHORT).show()
                    scheduleAllReminders()
                } catch (e: JSONException) {
                    showError("剪贴板内容不是有效的 JSON 数据")
                } catch (e: Exception) {
                    showError("解析出错: ${e.message}")
                }
            } else showError("剪贴板为空")
        } else showError("剪贴板无内容")
    }

    // ---------- 解析 JSON 并计算助手预估 ----------
    private fun parseUpgrades(jsonString: String): List<UpgradeItem> {
        val json = JSONObject(jsonString)
        val timestampUtcSec = json.getLong("timestamp")
        val startTime = timestampUtcSec * 1000L

        // 提取所有助手的冷却时间（按 helperId 存储）
        val helperCooldownMap = mutableMapOf<Int, Int>()
        val helpersArr = json.optJSONArray("helpers")
        if (helpersArr != null) {
            for (i in 0 until helpersArr.length()) {
                val hObj = helpersArr.getJSONObject(i)
                val hId = hObj.getInt("data")
                val cd = hObj.optInt("helper_cooldown", 0)
                helperCooldownMap[hId] = cd
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
                    val (category, reduction) = getCategoryAndReduction(key)

                    val helperId = when (category) {
                        "building", "hero" -> 93000000
                        "lab" -> 93000001
                        else -> 0
                    }

                    val endTimeMillis = startTime + timer * 1000L
                    val targetLvl = if (lvl > 0) lvl + 1 else 0

                    // 计算预估结束时间（若使用了助手）
                    var estimated = 0L
                    if (helper && reduction > 0) {
                        val cdSeconds = helperCooldownMap[helperId] ?: 0
                        estimated = calculateEstimatedEndTime(
                            startTime = startTime,
                            originalEndTime = endTimeMillis,
                            dailyReductionSeconds = reduction,
                            cooldownSeconds = cdSeconds
                        )
                    }

                    result.add(
                        UpgradeItem(
                            id = id,
                            endTimeMillis = endTimeMillis,
                            targetLevel = targetLvl,
                            helperRecurrent = helper,
                            dailyReductionSeconds = reduction,
                            estimatedEndTimeMillis = estimated
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

    /**
     * 模拟时间线，计算助手加速后的预估结束时间
     */
    private fun calculateEstimatedEndTime(
        startTime: Long,               // 导出时间（毫秒）
        originalEndTime: Long,
        dailyReductionSeconds: Long,   // 每次加速秒数（28800 或 3600）
        cooldownSeconds: Int           // 导出时的剩余冷却秒数
    ): Long {
        if (dailyReductionSeconds <= 0) return originalEndTime

        val accelSec = dailyReductionSeconds.toDouble()   // 一次加速量（秒）
        val workSec = 3600.0                              // 助手工作时间 1 小时
        val cooldownSec = 23 * 3600.0                     // 冷却 23 小时

        var currentTime = startTime
        var remaining = (originalEndTime - startTime).toDouble() // 剩余秒数

        // 第一次可用时间：startTime + cooldownSeconds
        var nextAvailable = startTime + cooldownSeconds * 1000L
        nextAvailable = adjustTo7am(nextAvailable)

        while (nextAvailable < originalEndTime && remaining > 0) {
            // 从 currentTime 到 nextAvailable，正常流逝
            val elapsed = (nextAvailable - currentTime) / 1000.0
            remaining -= elapsed
            if (remaining <= 0) {
                // 在等待期间已经完成
                return currentTime + (remaining + elapsed) * 1000L  // 实际完成时间
            }

            // 使用助手：需要完成 min(remaining, accelSec) 秒升级量
            val needSec = minOf(remaining, accelSec)
            val actualWorkSec = needSec / accelSec * workSec
            val finishTime = nextAvailable + (actualWorkSec * 1000).toLong()
            currentTime = finishTime
            remaining -= needSec

            if (remaining <= 0) {
                return finishTime
            }

            // 下一次可用时间 = 本次开始时间 + 23 小时，然后调整到7点后
            nextAvailable = nextAvailable + (23 * 3600 * 1000L)
            nextAvailable = adjustTo7am(nextAvailable)
        }

        // 没有更多助手可用，剩余时间按正常流逝
        return currentTime + (remaining * 1000).toLong()
    }

    /** 若时间戳对应的小时小于7，则推迟到当天7:00 */
    private fun adjustTo7am(timeMs: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMs
        if (cal.get(Calendar.HOUR_OF_DAY) < 7) {
            cal.set(Calendar.HOUR_OF_DAY, 7)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
        return timeMs
    }

    // ---------- 点击列表项：若使用了助手，可选择按预估时间更新提醒 ----------
    private fun onItemClick(item: UpgradeItem, position: Int) {
        if (!item.helperRecurrent || item.estimatedEndTimeMillis <= 0) return

        AlertDialog.Builder(this)
            .setTitle("助手提醒")
            .setMessage("是否将提醒时间更新为预估时间？")
            .setPositiveButton("更新") { _, _ ->
                currentUpgrades[position] = item.copy(
                    endTimeMillis = item.estimatedEndTimeMillis,
                    estimatedEndTimeMillis = 0
                )
                adapter.notifyItemChanged(position)
                scheduleAllReminders()
                Toast.makeText(this, "提醒已更新为预估时间", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 闹钟设置 ----------
    private fun scheduleAllReminders() {
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

        for (item in currentUpgrades) {
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