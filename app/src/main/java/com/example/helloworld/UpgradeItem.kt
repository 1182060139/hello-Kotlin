package com.example.helloworld

data class UpgradeItem(
    val id: Int,
    val endTimeMillis: Long,
    // 唯一标识，用于闹钟 requestCode
    val uniqueKey: String = "${id}_${endTimeMillis}"
)