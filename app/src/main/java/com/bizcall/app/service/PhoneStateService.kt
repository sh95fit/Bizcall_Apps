package com.bizcall.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import com.bizcall.app.receiver.CallReceiver

class PhoneStateService : Service() {

    companion object {
        const val CHANNEL_ID = "bizcall_monitor_channel"
        const val NOTIFICATION_ID = 1000
        private const val TAG = "PhoneStateService"
    }

    private val callReceiver = CallReceiver()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val filter = IntentFilter().apply {
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        }
        registerReceiver(callReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // 앱이 최근 실행 목록에서 스와이프로 제거될 때 호출됨
    // stopWithTask="false" 설정 시에만 동작
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "앱 종료 감지 — 서비스 재시작 예약")

        // 서비스 자기 자신을 재시작
        val restartIntent = Intent(applicationContext, PhoneStateService::class.java)
        startForegroundService(restartIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(callReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "CallReceiver 해제 실패: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BizCall 모니터링",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "업무 통화 감지 중"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BizCall")
            .setContentText("업무 통화 감지 중...")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .build()
    }
}
