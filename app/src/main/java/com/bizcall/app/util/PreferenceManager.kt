package com.bizcall.app.util

import android.content.Context

object PreferenceManager {

    private const val PREF_NAME = "bizcall_prefs"
    private const val KEY_PHONE_ID = "phone_id"
    private const val KEY_TOKEN = "token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_REGISTERED = "is_registered"

    // 저장
    fun savePhoneInfo(context: Context, phoneId: String, token: String, deviceId: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PHONE_ID, phoneId)
            .putString(KEY_TOKEN, token)
            .putString(KEY_DEVICE_ID, deviceId)
            .putBoolean(KEY_REGISTERED, true)
            .apply()
    }

    // 조회
    fun getPhoneId(context: Context): String? =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PHONE_ID, null)

    fun getToken(context: Context): String? =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)

    fun getDeviceId(context: Context): String? =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_ID, null)

    fun isRegistered(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REGISTERED, false)

    // 초기화 (폰 비활성화 시)
    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
