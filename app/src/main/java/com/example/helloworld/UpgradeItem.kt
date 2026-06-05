package com.example.helloworld

data class UpgradeItem(
    val id: Int,
    val endTimeMillis: Long,               // 原始结束时间（基于 timer+timestamp）
    val targetLevel: Int = 0,
    val helperRecurrent: Boolean = false,
    val helperId: Int = 0,                 // 对应 helpers 中的 data
    val dailyReductionSeconds: Long = 0,   // 助手每天减少的秒数（建筑8h=28800，实验室1h=3600）
    var estimatedEndTimeMillis: Long = 0,  // 预估结束时间（0 表示未计算）
    val uniqueKey: String = "${id}_${endTimeMillis}"
)