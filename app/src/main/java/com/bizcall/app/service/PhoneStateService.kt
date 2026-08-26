package com.bizcall.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log
import com.bizcall.app.receiver.CallReceiver
import com.bizcall.app.upload.S3Uploader
import com.bizcall.app.util.DeviceDetector
import com.bizcall.app.util.PreferenceManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        // 통화 상태 수신 (발신번호 감지용 — Samsung 모드에서도 유지)
        val filter = IntentFilter().apply {
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        }
        registerReceiver(callReceiver, filter)

        // Samsung One UI 기기면 자동 녹음 감지 시작
        if (DeviceDetector.isSamsungOneUi()) {
            startSamsungDetector()
        } else {
            Log.d(TAG, "비삼성 기기 — 직접 녹음 모드 (CallRecordingService 사용)")
        }
    }

    private fun startSamsungDetector() {
        if (!PreferenceManager.isRegistered(this)) {
            Log.w(TAG, "미등록 기기 — 감지 시작 안 함")
            return
        }

        samsungDetector = SamsungRecordingDetector { filePath ->
            Log.d(TAG, "삼성 자동 녹음 감지됨: $filePath")
            handleNewSamsungRecording(filePath)
        }

        val started = samsungDetector?.start() ?: false
        if (started) {
            Log.d(TAG, "Samsung 자동 녹음 감지 시작 완료 (One UI ${DeviceDetector.getOneUiVersion()})")
        } else {
            Log.w(TAG, "Samsung 녹음 경로 없음 — 감지 실패")
        }
    }

    /**
     * 삼성 자동 녹음 파일 감지 후 처리
     * 1) CallLog에서 최근 통화 메타데이터 조회
     * 2) BizCall 표준 파일명으로 변환
     * 3) S3 업로드 큐 등록
     */
    private fun handleNewSamsungRecording(filePath: String) {
        try {
            val file = File(filePath)

            // CallLog에서 최근 통화 정보 조회 (파일 생성 기준 ±30초 이내)
            val meta = queryRecentCallLog(System.currentTimeMillis())
            val direction = meta?.direction ?: "unknown"
            val callerNumber = meta?.number ?: "unknown"
            val callStartTime = meta?.startTime ?: file.lastModified()

            Log.d(TAG, "메타데이터: direction=$direction, number=$callerNumber, time=$callStartTime")

            // 기존 파이프라인과 동일한 S3 키 구조로 업로드 큐 등록
            // recordings/{phone_id}_{direction}_{callerNumber}_{yyyyMMddHHmmss}.m4a
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

    /**
     * CallLog에서 가장 최근 통화 기록 조회
     * 파일 감지 시점 기준 ±60초 이내 통화만 매칭
     */
    private fun queryRecentCallLog(detectedAt: Long): CallMeta? {
        return try {
            val cursor: Cursor? = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                ),
                null, null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val number = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: "unknown"
                    val type = c.getInt(c.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val date = c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    val duration = c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DURATION))

                    // 파일 감지 시점과 통화 시작 시각 차이가 60초 이내인지 확인
                    val diffMs = Math.abs(detectedAt - date)
                    if (diffMs > 60_000L) {
                        Log.w(TAG, "CallLog 시간 불일치 (${diffMs}ms) — 메타데이터 unknown 처리")
                        return null
                    }

                    val direction = when (type) {
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        else -> "unknown"
                    }

                    CallMeta(number = number, direction = direction, startTime = date)
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "CallLog 조회 실패: ${e.message}")
            null
        }
    }

    data class CallMeta(
        val number: String,
        val direction: String,
        val startTime: Long
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, PhoneStateService::class.java)
        startService(restartIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        samsungDetector?.stop()
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
