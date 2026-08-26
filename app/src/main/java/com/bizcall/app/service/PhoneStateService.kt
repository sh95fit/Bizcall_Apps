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
import com.bizcall.app.upload.S3Uploader
import com.bizcall.app.util.DeviceDetector
import com.bizcall.app.util.PendingCallMeta
import com.bizcall.app.util.PreferenceManager
import com.bizcall.app.util.RecordingMode
import java.io.File

class PhoneStateService : Service() {

    companion object {
        const val CHANNEL_ID = "bizcall_monitor_channel"
        const val NOTIFICATION_ID = 1000
        private const val TAG = "PhoneStateService"
    }

    private val callReceiver = CallReceiver()
    private var samsungDetector: SamsungRecordingDetector? = null

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

        // 모든 모드에서 CallReceiver 공통 등록
        // SAMSUNG: IDLE 시 PendingCallMeta.push
        // DIRECT_MIC: CallRecordingService 트리거
        registerReceiver(
            callReceiver,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        )

        initRecordingStrategy()
    }

    private fun initRecordingStrategy() {
        val mode = PreferenceManager.getRecordingMode(this)
        Log.i(TAG, "녹음 전략: ${mode.displayName}")

        when (mode) {
            RecordingMode.SAMSUNG -> {
                if (!DeviceDetector.isSamsungOneUi()) {
                    Log.w(TAG, "SAMSUNG 모드이나 Samsung One UI 기기 아님 — SamsungRecordingDetector 비활성")
                    return
                }
                startSamsungDetector()
            }
            RecordingMode.DIRECT_MIC -> {
                // CallReceiver → CallRecordingService 흐름으로 동작
                // SamsungRecordingDetector 실행 안 함
                Log.d(TAG, "DIRECT_MIC 모드 — CallRecordingService 활성")
            }
        }
    }

    private fun startSamsungDetector() {
        if (!PreferenceManager.isRegistered(this)) {
            Log.w(TAG, "미등록 기기 — Samsung 감지 시작 안 함")
            return
        }

        samsungDetector = SamsungRecordingDetector { filePath, meta ->
            Log.d(TAG, "Samsung 자동 녹음 감지됨: $filePath")
            handleNewSamsungRecording(filePath, meta)
        }

        val started = samsungDetector?.start() ?: false
        Log.i(
            TAG,
            if (started) "Samsung 감지 시작 완료 (One UI ${DeviceDetector.getOneUiVersion()})"
            else "Samsung 녹음 경로 없음 — 감지 실패"
        )
    }

    /**
     * Samsung 자동 녹음 파일 처리
     *
     * meta: PendingCallMeta.pop()에서 꺼낸 값
     *   - 정상: 통화의 direction, callerNumber, callStartTime 정확히 포함
     *   - null: 앱 재시작 직후 / 큐 만료 → unknown 폴백, 파이프라인이 처리
     */
    private fun handleNewSamsungRecording(filePath: String, meta: PendingCallMeta.CallMeta?) {
        try {
            val file = File(filePath)
            val direction = meta?.direction ?: "unknown"
            val callerNumber = meta?.callerNumber ?: "unknown"
            val callStartTime = meta?.callStartTime ?: file.lastModified()

            Log.d(TAG, "업로드 큐 등록: direction=$direction, number=$callerNumber, startTime=$callStartTime")

            S3Uploader.enqueue(
                context = this,
                filePath = filePath,
                direction = direction,
                callerNumber = callerNumber,
                callStartTime = callStartTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Samsung 녹음 처리 오류: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        startService(Intent(applicationContext, PhoneStateService::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        samsungDetector?.stop()
        PendingCallMeta.clear() // 서비스 종료 시 큐 초기화
        try {
            unregisterReceiver(callReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Receiver 해제 오류: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BizCall 모니터",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "업무 통화 감지 중" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BizCall")
            .setContentText("업무 통화 감지 중...")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .build()
}
