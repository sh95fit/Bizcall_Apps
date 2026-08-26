package com.bizcall.app.util

import android.content.Context
import java.util.UUID

object DeviceIdManager {

    private const val PREF_NAME = "bizcall_device"
    private const val KEY_DEVICE_ID = "device_id"

    // 기기 고유 ID 반환 (없으면 생성 후 저장)
    fun getOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }
}
