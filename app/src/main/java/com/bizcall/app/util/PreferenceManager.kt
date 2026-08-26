package com.bizcall.app.util

import android.content.Context

object PreferenceManager {

    private const val PREF_NAME = "bizcall_prefs"
    private const val KEY_PHONE_ID = "phone_id"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_REGISTERED = "is_registered"
    private const val KEY_RECORDING_MODE = "recording_mode"

    fun savePhoneInfo(context: Context, phoneId: String, deviceId: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHONE_ID, phoneId)
            .putString(KEY_DEVICE_ID, deviceId)
            .putBoolean(KEY_REGISTERED, true)
            .apply()
    }

    fun getPhoneId(context: Context): String? =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PHONE_ID, null)

    fun getDeviceId(context: Context): String? =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_ID, null)

    fun isRegistered(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REGISTERED, false)

    // RecordingMode: 현재는 SAMSUNG 고정, 추후 전환 가능하도록 저장/조회 구조 유지
    fun setRecordingMode(context: Context, mode: RecordingMode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECORDING_MODE, mode.key)
            .apply()
    }

    fun getRecordingMode(context: Context): RecordingMode {
        val key = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECORDING_MODE, RecordingMode.SAMSUNG.key)
        return RecordingMode.entries.firstOrNull { it.key == key } ?: RecordingMode.SAMSUNG
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
